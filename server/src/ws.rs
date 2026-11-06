//! WS 连接管理：在线表、设备列表广播、定位请求路由与超时

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::extract::ws::{Message as WsMessage, WebSocket};
use futures_util::{SinkExt, StreamExt};
use tokio::sync::mpsc;

use crate::protocol::{ClientMsg, DeviceEntry, ServerMsg};
use crate::AppState;

/// 定位请求超时
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

pub struct PendingReq {
    pub requester: i64,
}

#[derive(Default)]
pub struct Hub {
    /// 在线连接表：user_id -> (出站通道, 会话代号)。同一账号重连时新会话覆盖旧会话，
    /// 旧会话退出时凭代号比对，避免误删新会话的在线状态。
    conns: Mutex<HashMap<i64, (mpsc::UnboundedSender<String>, u64)>>,
    /// 进行中的定位请求：request_id -> 请求方
    pending: Mutex<HashMap<String, PendingReq>>,
    next_gen: std::sync::atomic::AtomicU64,
}

impl Hub {
    pub fn insert(&self, user_id: i64, tx: mpsc::UnboundedSender<String>) -> u64 {
        let gen = self
            .next_gen
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        self.conns.lock().unwrap().insert(user_id, (tx, gen));
        gen
    }

    /// 仅当会话代号匹配（仍是自己这届）时才移除
    pub fn remove(&self, user_id: &i64, gen: u64) {
        let mut conns = self.conns.lock().unwrap();
        if matches!(conns.get(user_id), Some((_, g)) if *g == gen) {
            conns.remove(user_id);
        }
    }

    pub fn is_online(&self, user_id: i64) -> bool {
        self.conns.lock().unwrap().contains_key(&user_id)
    }

    pub fn send_to(&self, user_id: i64, msg: &ServerMsg) -> bool {
        let json = serde_json::to_string(msg).unwrap_or_default();
        if let Some((tx, _)) = self.conns.lock().unwrap().get(&user_id) {
            tx.send(json).is_ok()
        } else {
            false
        }
    }

    fn take_pending(&self, request_id: &str) -> Option<PendingReq> {
        self.pending.lock().unwrap().remove(request_id)
    }
}

/// 全量设备列表（含在线状态）推给所有在线连接
pub fn broadcast_device_list(state: &Arc<AppState>) {
    let users = state.db.list_users();
    let devices: Vec<DeviceEntry> = users
        .into_iter()
        .map(|(id, name)| DeviceEntry {
            id,
            name,
            online: state.hub.is_online(id),
        })
        .collect();
    let msg = ServerMsg::DeviceList { devices };
    let json = serde_json::to_string(&msg).unwrap_or_default();
    let conns = state.hub.conns.lock().unwrap();
    for (tx, _) in conns.values() {
        let _ = tx.send(json.clone());
    }
}

/// 单个 WS 会话主循环
pub async fn run_session(state: Arc<AppState>, user_id: i64, username: String, socket: WebSocket) {
    let (out_tx, mut out_rx) = mpsc::unbounded_channel::<String>();
    let gen = state.hub.insert(user_id, out_tx);
    state.db.touch(user_id);
    tracing::info!("设备上线: {user_id} {username}");

    // 鉴权通过 + 首次设备列表
    let hello = serde_json::to_string(&ServerMsg::AuthOk {
        device_id: user_id,
        name: username.clone(),
    })
    .unwrap_or_default();
    if let Some((tx, _)) = state.hub.conns.lock().unwrap().get(&user_id) {
        let _ = tx.send(hello);
    }
    broadcast_device_list(&state);

    let (mut sink, mut stream) = socket.split();

    loop {
        tokio::select! {
            // 出站：把通道里的 JSON 写回 socket
            out = out_rx.recv() => {
                match out {
                    Some(text) => {
                        if sink.send(WsMessage::Text(text)).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
            // 入站：读客户端消息
            incoming = stream.next() => {
                match incoming {
                    Some(Ok(WsMessage::Text(text))) => {
                        handle_client_msg(&state, user_id, &username, &text).await;
                    }
                    Some(Ok(WsMessage::Close(_))) | None => break,
                    Some(Ok(_)) => {} // 二进制/pong 帧忽略
                    Some(Err(e)) => {
                        tracing::debug!("ws 读取错误 user={user_id}: {e}");
                        break;
                    }
                }
            }
        }
    }

    state.hub.remove(&user_id, gen);
    tracing::info!("设备离线: {user_id} {username}");
    broadcast_device_list(&state);
}

async fn handle_client_msg(state: &Arc<AppState>, user_id: i64, _username: &str, text: &str) {
    let msg: ClientMsg = match serde_json::from_str(text) {
        Ok(m) => m,
        Err(e) => {
            tracing::debug!("非法消息 user={user_id}: {e}");
            return;
        }
    };
    match msg {
        ClientMsg::Ping => {
            state.hub.send_to(user_id, &ServerMsg::Pong);
        }
        ClientMsg::RequestLocation { target_id } => {
            handle_request_location(state, user_id, target_id).await;
        }
        ClientMsg::LocationReport { request_id, lat, lng, accuracy, ts } => {
            if let Some(p) = state.hub.take_pending(&request_id) {
                state.hub.send_to(
                    p.requester,
                    &ServerMsg::LocationResult { request_id, lat, lng, accuracy, ts },
                );
            }
        }
        ClientMsg::LocationError { request_id, reason } => {
            if let Some(p) = state.hub.take_pending(&request_id) {
                state.hub.send_to(
                    p.requester,
                    &ServerMsg::LocationError { request_id, reason },
                );
            }
        }
    }
}

async fn handle_request_location(state: &Arc<AppState>, from_id: i64, target_id: i64) {
    if target_id == from_id {
        state.hub.send_to(from_id, &ServerMsg::LocationError {
            request_id: String::new(),
            reason: "不能请求自己的位置".into(),
        });
        return;
    }
    if !state.hub.is_online(target_id) {
        state.hub.send_to(from_id, &ServerMsg::LocationError {
            request_id: String::new(),
            reason: "对方不在线".into(),
        });
        return;
    }
    let request_id = uuid::Uuid::new_v4().to_string();
    state.hub.pending.lock().unwrap().insert(
        request_id.clone(),
        PendingReq { requester: from_id },
    );

    let from_name = state
        .db
        .get_user_by_id(from_id)
        .unwrap_or_else(|| "unknown".into());
    let sent = state.hub.send_to(
        target_id,
        &ServerMsg::LocationRequest { request_id: request_id.clone(), from_id, from_name },
    );
    if !sent {
        state.hub.take_pending(&request_id);
        state.hub.send_to(from_id, &ServerMsg::LocationError {
            request_id,
            reason: "对方不在线".into(),
        });
        return;
    }

    // 超时兜底
    let st = Arc::clone(state);
    let rid = request_id.clone();
    tokio::spawn(async move {
        tokio::time::sleep(REQUEST_TIMEOUT).await;
        if let Some(p) = st.hub.take_pending(&rid) {
            st.hub.send_to(p.requester, &ServerMsg::LocationError {
                request_id: rid,
                reason: "对方未响应（超时）".into(),
            });
        }
    });
}
