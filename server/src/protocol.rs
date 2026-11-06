//! WS 消息协议：JSON 文本帧，统一信封 {"type": "...", ...}

/// 客户端 -> 服务端
#[derive(Debug, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMsg {
    /// 应用层心跳
    Ping,
    /// 请求某台设备的当前位置
    RequestLocation { target_id: i64 },
    /// 被请求方上报定位结果
    LocationReport {
        request_id: String,
        lat: f64,
        lng: f64,
        /// 水平精度（米），可选
        accuracy: Option<f64>,
        /// 定位时刻 Unix 秒
        ts: i64,
    },
    /// 被请求方定位失败
    LocationError { request_id: String, reason: String },
}

/// 设备条目
#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
pub struct DeviceEntry {
    pub id: i64,
    pub name: String,
    pub online: bool,
}

/// 服务端 -> 客户端
#[derive(Debug, serde::Serialize, serde::Deserialize, Clone)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMsg {
    /// 鉴权通过
    AuthOk { device_id: i64, name: String },
    /// 全量设备列表（任何人上下线都会重推）
    DeviceList { devices: Vec<DeviceEntry> },
    Pong,
    /// 服务端把定位请求转发给被请求方
    LocationRequest {
        request_id: String,
        from_id: i64,
        from_name: String,
    },
    /// 定位结果转发给请求方
    LocationResult {
        request_id: String,
        lat: f64,
        lng: f64,
        accuracy: Option<f64>,
        ts: i64,
    },
    /// 定位失败/超时
    LocationError { request_id: String, reason: String },
}
