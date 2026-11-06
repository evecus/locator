//! HTTP 接口：注册 / 登录 / 健康检查 / WS 升级

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Path, Query, State};
use axum::http::{header, HeaderMap, StatusCode};
use axum::response::{Html, IntoResponse, Response};
use axum::routing::{delete, get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};

use crate::{auth, db, web, ws, AppState};

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/api/register", post(register))
        .route("/api/login", post(login))
        .route("/health", get(health))
        .route("/ws", get(ws_handler))
        // 网页
        .route("/admin", get(|| async { Html(web::render(web::ADMIN_PAGE)) }))
        .route("/user", get(|| async { Html(web::render(web::USER_PAGE)) }))
        // 管理接口
        .route("/admin/api/login", post(admin_login))
        .route("/admin/api/users", get(admin_users))
        .route("/admin/api/users/:id", delete(admin_delete_user))
        .route(
            "/admin/api/invites",
            get(admin_invites_list).post(admin_invites_add),
        )
        .with_state(state)
}

async fn health() -> &'static str {
    "ok"
}

#[derive(Deserialize)]
struct AuthReq {
    username: String,
    password: String,
    #[serde(default)]
    invite_code: String,
}

#[derive(Serialize)]
struct AuthResp {
    token: String,
    user_id: i64,
    username: String,
}

fn err(status: StatusCode, msg: &str) -> Response {
    (status, Json(serde_json::json!({ "error": msg }))).into_response()
}

async fn register(
    State(state): State<Arc<AppState>>,
    Json(req): Json<AuthReq>,
) -> Response {
    let username = req.username.trim();
    if username.len() < 2 || username.len() > 32 {
        return err(StatusCode::BAD_REQUEST, "用户名长度需在 2-32 之间");
    }
    if req.password.len() < 6 {
        return err(StatusCode::BAD_REQUEST, "密码至少 6 位");
    }
    if req.invite_code.trim().is_empty() {
        return err(StatusCode::BAD_REQUEST, "邀请码不能为空");
    }
    if let Err(e) = state.db.take_invite(req.invite_code.trim()) {
        return err(StatusCode::FORBIDDEN, &e);
    }
    let hash = match auth::hash_password(&req.password) {
        Ok(h) => h,
        Err(e) => return err(StatusCode::INTERNAL_SERVER_ERROR, &e),
    };
    let user_id = match state.db.create_user(username, &hash) {
        Ok(id) => id,
        Err(e) => return err(StatusCode::CONFLICT, &e),
    };
    let token = match auth::make_token(user_id, username, &state.jwt_secret) {
        Ok(t) => t,
        Err(e) => return err(StatusCode::INTERNAL_SERVER_ERROR, &e),
    };
    (
        StatusCode::OK,
        Json(AuthResp { token, user_id, username: username.to_string() }),
    )
        .into_response()
}

async fn login(
    State(state): State<Arc<AppState>>,
    Json(req): Json<AuthReq>,
) -> Response {
    let username = req.username.trim();
    let user = state.db.get_user_by_name(username);
    let Some((user_id, name, hash)) = user else {
        return err(StatusCode::UNAUTHORIZED, "用户名或密码错误");
    };
    if !auth::verify_password(&req.password, &hash) {
        return err(StatusCode::UNAUTHORIZED, "用户名或密码错误");
    }
    let token = match auth::make_token(user_id, &name, &state.jwt_secret) {
        Ok(t) => t,
        Err(e) => return err(StatusCode::INTERNAL_SERVER_ERROR, &e),
    };
    (StatusCode::OK, Json(AuthResp { token, user_id, username: name })).into_response()
}

/// WS 握手：/ws?token=JWT，鉴权失败直接拒绝升级
async fn ws_handler(
    Query(params): Query<HashMap<String, String>>,
    State(state): State<Arc<AppState>>,
    upgrade: axum::extract::ws::WebSocketUpgrade,
) -> Response {
    let token = params.get("token").cloned().unwrap_or_default();
    let Some((user_id, username)) = auth::verify_token(&token, &state.jwt_secret) else {
        tracing::warn!("WS 握手被拒绝: token 无效或签名密钥不匹配（可能换过数据库）");
        return err(StatusCode::UNAUTHORIZED, "token 无效");
    };
    upgrade
        .on_upgrade(move |socket| ws::run_session(state, user_id, username, socket))
        .into_response()
}

// ---------- 管理后台 API ----------

/// 校验 Authorization: Bearer <jwt>，且必须是管理员（user_id == 0）
fn check_admin(state: &Arc<AppState>, headers: &HeaderMap) -> bool {
    let tok = headers
        .get(header::AUTHORIZATION)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.strip_prefix("Bearer "))
        .unwrap_or("");
    matches!(auth::verify_token(tok, &state.jwt_secret), Some((0, _)))
}

#[derive(Deserialize)]
struct AdminLoginReq {
    password: String,
}

async fn admin_login(
    State(state): State<Arc<AppState>>,
    Json(req): Json<AdminLoginReq>,
) -> Response {
    let Some(hash) = state.db.get_admin_pass() else {
        return err(
            StatusCode::PRECONDITION_FAILED,
            "未设置管理员密码，请在服务器执行: locator-server passwd --password xxx",
        );
    };
    if !auth::verify_password(&req.password, &hash) {
        return err(StatusCode::UNAUTHORIZED, "密码错误");
    }
    let token = match auth::make_token(0, "admin", &state.jwt_secret) {
        Ok(t) => t,
        Err(e) => return err(StatusCode::INTERNAL_SERVER_ERROR, &e),
    };
    (StatusCode::OK, Json(serde_json::json!({ "token": token }))).into_response()
}

#[derive(Serialize)]
struct AdminUser {
    id: i64,
    name: String,
    online: bool,
    last_seen: i64,
}

async fn admin_users(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !check_admin(&state, &headers) {
        return err(StatusCode::UNAUTHORIZED, "未授权");
    }
    let users: Vec<AdminUser> = state
        .db
        .list_users_full()
        .into_iter()
        .map(|(id, name, last_seen)| AdminUser {
            id,
            name,
            online: state.hub.is_online(id),
            last_seen,
        })
        .collect();
    (StatusCode::OK, Json(serde_json::json!({ "users": users }))).into_response()
}

async fn admin_delete_user(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Path(id): Path<i64>,
) -> Response {
    if !check_admin(&state, &headers) {
        return err(StatusCode::UNAUTHORIZED, "未授权");
    }
    if !state.db.delete_user(id) {
        return err(StatusCode::NOT_FOUND, "设备不存在");
    }
    (StatusCode::OK, Json(serde_json::json!({ "ok": true }))).into_response()
}

async fn admin_invites_list(State(state): State<Arc<AppState>>, headers: HeaderMap) -> Response {
    if !check_admin(&state, &headers) {
        return err(StatusCode::UNAUTHORIZED, "未授权");
    }
    let invites: Vec<_> = state
        .db
        .list_invites()
        .into_iter()
        .map(|(code, used, created_at)| {
            serde_json::json!({ "code": code, "used": used, "created_at": created_at })
        })
        .collect();
    (StatusCode::OK, Json(serde_json::json!({ "invites": invites }))).into_response()
}

#[derive(Deserialize)]
struct GenInvitesReq {
    #[serde(default = "default_count")]
    count: u32,
}

fn default_count() -> u32 {
    1
}

async fn admin_invites_add(
    State(state): State<Arc<AppState>>,
    headers: HeaderMap,
    Json(req): Json<GenInvitesReq>,
) -> Response {
    if !check_admin(&state, &headers) {
        return err(StatusCode::UNAUTHORIZED, "未授权");
    }
    let count = req.count.clamp(1, 50);
    let codes: Vec<String> = (0..count).map(|_| db::gen_invite_code()).collect();
    state.db.add_invites(&codes);
    (StatusCode::OK, Json(serde_json::json!({ "codes": codes }))).into_response()
}
