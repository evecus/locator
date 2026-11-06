//! SQLite 存储：users / invites / config

use std::sync::Mutex;

use rusqlite::Connection;

pub struct Db(pub Mutex<Connection>);

fn now() -> i64 {
    chrono::Utc::now().timestamp()
}

impl Db {
    pub fn open(path: &str) -> rusqlite::Result<Db> {
        let conn = Connection::open(path)?;
        conn.execute_batch(
            "PRAGMA journal_mode = WAL;
             PRAGMA foreign_keys = ON;

             CREATE TABLE IF NOT EXISTS users (
                 id            INTEGER PRIMARY KEY AUTOINCREMENT,
                 username      TEXT UNIQUE NOT NULL,
                 password_hash TEXT NOT NULL,
                 last_seen     INTEGER NOT NULL DEFAULT 0
             );

             CREATE TABLE IF NOT EXISTS invites (
                 code       TEXT PRIMARY KEY,
                 used_by    INTEGER,
                 created_at INTEGER NOT NULL
             );

             CREATE TABLE IF NOT EXISTS config (
                 key   TEXT PRIMARY KEY,
                 value TEXT NOT NULL
             );",
        )?;
        Ok(Db(Mutex::new(conn)))
    }

    /// 创建用户，返回 user id
    pub fn create_user(&self, username: &str, password_hash: &str) -> Result<i64, String> {
        let conn = self.0.lock().unwrap();
        conn.execute(
            "INSERT INTO users (username, password_hash, last_seen) VALUES (?1, ?2, ?3)",
            rusqlite::params![username, password_hash, now()],
        )
        .map_err(|e| {
            if e.to_string().contains("UNIQUE") {
                "用户名已存在".to_string()
            } else {
                e.to_string()
            }
        })?;
        Ok(conn.last_insert_rowid())
    }

    /// 按 用户名 查询，返回 (id, username, password_hash)
    pub fn get_user_by_name(&self, username: &str) -> Option<(i64, String, String)> {
        let conn = self.0.lock().unwrap();
        conn.query_row(
            "SELECT id, username, password_hash FROM users WHERE username = ?1",
            [username],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .ok()
    }

    pub fn get_user_by_id(&self, id: i64) -> Option<String> {
        let conn = self.0.lock().unwrap();
        conn.query_row("SELECT username FROM users WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .ok()
    }

    /// 全部用户 (id, username)，用于设备列表
    pub fn list_users(&self) -> Vec<(i64, String)> {
        let conn = self.0.lock().unwrap();
        let mut stmt = match conn.prepare("SELECT id, username FROM users ORDER BY id") {
            Ok(s) => s,
            Err(_) => return vec![],
        };
        stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
            .map(|rows| rows.filter_map(|r| r.ok()).collect())
            .unwrap_or_default()
    }

    pub fn touch(&self, id: i64) {
        let conn = self.0.lock().unwrap();
        let _ = conn.execute(
            "UPDATE users SET last_seen = ?1 WHERE id = ?2",
            rusqlite::params![now(), id],
        );
    }

    pub fn add_invites(&self, codes: &[String]) {
        let conn = self.0.lock().unwrap();
        for code in codes {
            let _ = conn.execute(
                "INSERT OR IGNORE INTO invites (code, created_at) VALUES (?1, ?2)",
                rusqlite::params![code, now()],
            );
        }
    }

    /// 核销邀请码（一次性），失败返回原因
    pub fn take_invite(&self, code: &str) -> Result<(), String> {
        let conn = self.0.lock().unwrap();
        let used_by: Option<i64> = conn
            .query_row(
                "SELECT used_by FROM invites WHERE code = ?1",
                [code],
                |r| r.get(0),
            )
            .map_err(|_| "邀请码无效".to_string())?;
        if used_by.is_some() {
            return Err("邀请码已被使用".to_string());
        }
        let n = conn
            .execute(
                "UPDATE invites SET used_by = ?1 WHERE code = ?2 AND used_by IS NULL",
                rusqlite::params![now(), code],
            )
            .map_err(|e| e.to_string())?;
        if n == 0 {
            return Err("邀请码已被使用".to_string());
        }
        Ok(())
    }

    /// 取/存 JWT 密钥（首次自动生成并持久化，重启后 token 仍有效）
    pub fn get_or_set_jwt_secret(&self, explicit: Option<&str>) -> String {
        if let Some(s) = explicit {
            let conn = self.0.lock().unwrap();
            let _ = conn.execute(
                "INSERT OR REPLACE INTO config (key, value) VALUES ('jwt_secret', ?1)",
                [s],
            );
            return s.to_string();
        }
        let conn = self.0.lock().unwrap();
        let existing: Option<String> = conn
            .query_row(
                "SELECT value FROM config WHERE key = 'jwt_secret'",
                [],
                |r| r.get(0),
            )
            .ok();
        if let Some(s) = existing {
            return s;
        }
        let secret = uuid::Uuid::new_v4().to_string() + &uuid::Uuid::new_v4().to_string();
        let _ = conn.execute(
            "INSERT OR REPLACE INTO config (key, value) VALUES ('jwt_secret', ?1)",
            [&secret],
        );
        secret
    }
}

/// 生成邀请码：XXXX-XXXX（去掉易混淆字符）
pub fn gen_invite_code() -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    use rand::Rng;
    let mut rng = rand::thread_rng();
    let mut pick = |n: usize| -> String {
        (0..n)
            .map(|_| ALPHABET[rng.gen_range(0..ALPHABET.len())] as char)
            .collect()
    };
    let a = pick(4);
    let b = pick(4);
    format!("{a}-{b}")
}
