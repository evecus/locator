//! HTTP 接口：注册 / 登录 / 健康检查 / WS 升级

use std::collections::HashMap;
use std::sync::Arc;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::{Deserialize, Serialize};

use crate::{auth, ws, AppState};

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/api/register", post(register))
        .route("/api/login", post(login))
        .route("/health", get(health))
        .route("/ws", get(ws_handler))
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
        return err(StatusCode::UNAUTHORIZED, "token 无效");
    };
    upgrade
        .on_upgrade(move |socket| ws::run_session(state, user_id, username, socket))
        .into_response()
}
