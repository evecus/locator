//! locator-server：位置共享服务端
//!
//! 子命令：
//!   serve   启动 HTTP/WS 服务
//!   invite  生成邀请码

mod auth;
mod db;
mod http;
mod protocol;
mod web;
mod ws;

use std::sync::Arc;

use clap::{Parser, Subcommand};

#[derive(Parser)]
#[command(name = "locator-server", version, about = "位置共享服务端（单二进制 + SQLite）")]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// 启动服务（HTTP/WS，建议前置 Caddy/Nginx 做 TLS）
    Serve {
        #[arg(long, default_value = "0.0.0.0:8080")]
        addr: String,
        #[arg(long = "db", default_value = "locator.db")]
        db_path: String,
        #[arg(long, help = "JWT 密钥；缺省则首次自动生成并保存到数据库")]
        jwt_secret: Option<String>,
    },
    /// 生成一次性邀请码
    Invite {
        #[arg(long = "db", default_value = "locator.db")]
        db_path: String,
        #[arg(long, default_value_t = 1)]
        count: u32,
    },
    /// 设置 /admin 管理页的登录密码
    Passwd {
        #[arg(long = "db", default_value = "locator.db")]
        db_path: String,
        #[arg(long)]
        password: String,
    },
}

pub struct AppState {
    pub db: db::Db,
    pub hub: ws::Hub,
    pub jwt_secret: String,
}

#[tokio::main]
async fn main() {
    tracing_subscriber::fmt()
        .with_target(false)
        .with_level(true)
        .init();
    match Cli::parse().cmd {
        Cmd::Serve { addr, db_path, jwt_secret } => serve(&addr, &db_path, jwt_secret).await,
        Cmd::Invite { db_path, count } => invite(&db_path, count),
        Cmd::Passwd { db_path, password } => passwd(&db_path, &password),
    }
}

fn passwd(db_path: &str, password: &str) {
    if password.len() < 6 {
        eprintln!("密码至少 6 位");
        std::process::exit(1);
    }
    let database = db::Db::open(db_path).unwrap_or_else(|e| panic!("打开数据库失败: {e}"));
    let hash = auth::hash_password(password).unwrap_or_else(|e| panic!("哈希失败: {e}"));
    database.set_admin_pass(&hash);
    println!("管理员密码已设置，访问 /admin 登录");
}

async fn serve(addr: &str, db_path: &str, jwt_secret: Option<String>) {
    let database = db::Db::open(db_path).unwrap_or_else(|e| panic!("打开数据库失败: {e}"));
    let secret = database.get_or_set_jwt_secret(jwt_secret.as_deref());
    let state = Arc::new(AppState {
        db: database,
        hub: ws::Hub::default(),
        jwt_secret: secret,
    });
    let app = http::router(state);
    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .unwrap_or_else(|e| panic!("监听 {addr} 失败: {e}"));
    tracing::info!("locator-server 已启动: http://{addr}");
    axum::serve(listener, app).await.unwrap();
}

fn invite(db_path: &str, count: u32) {
    let database = db::Db::open(db_path).unwrap_or_else(|e| panic!("打开数据库失败: {e}"));
    let mut codes = Vec::new();
    for _ in 0..count {
        codes.push(db::gen_invite_code());
    }
    database.add_invites(&codes);
    for c in &codes {
        println!("{c}");
    }
}
