//! Minimal JNI bridge that runs an embedded Arti (Tor) client and exposes a
//! local SOCKS5 proxy for the app to route `.onion` requests through. Kept
//! deliberately small: it uses only `TorClient::connect` (a stable arti-client
//! API) plus a hand-rolled SOCKS5 CONNECT handler, so it isn't coupled to the
//! internals of the `arti` binary crate.

use std::net::{Ipv4Addr, TcpListener as StdTcpListener};
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Arc, Mutex as StdMutex};

use anyhow::{anyhow, Result};
use arti_client::config::CfgPath;
use arti_client::{TorClient, TorClientConfig};
use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::OnceCell;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::runtime::Runtime;
use tor_rtcompat::PreferredRuntime;

/// Bootstrap progress 0..100, or -1 on failure. Polled by the Kotlin side.
static PROGRESS: AtomicI32 = AtomicI32::new(0);
static RUNTIME: OnceCell<Runtime> = OnceCell::new();
/// The SOCKS port of the running (or bootstrapping) client, if any. Cleared
/// when a start fails, so a later start can try again in the same process.
static RUNNING: StdMutex<Option<u16>> = StdMutex::new(None);

fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        Runtime::new().expect("failed to create Tokio runtime for Arti")
    })
}

fn init_logging() {
    static ONCE: OnceCell<()> = OnceCell::new();
    ONCE.get_or_init(|| {
        use tracing_subscriber::prelude::*;
        // Warnings and errors from the Tor stack, and this bridge's own phase
        // logs, to logcat under ArtiJni.
        let filter = tracing_subscriber::EnvFilter::new("warn,lf_arti=info");
        let _ = tracing_subscriber::registry()
            .with(paranoid_android::layer("ArtiJni"))
            .with(filter)
            .try_init();
        // A panic on a background Tokio task is otherwise swallowed silently.
        std::panic::set_hook(Box::new(|info| {
            tracing::error!("PANIC: {info}");
        }));
    });
}

/// Start bootstrapping Tor and serving SOCKS5 on a free port of 127.0.0.1,
/// chosen here so another Tor on the phone (Orbot, Tor Browser) cannot hold
/// it. Returns the port, or -1. While a client runs or bootstraps, returns
/// its port again; after a failure, starts afresh.
#[no_mangle]
pub extern "system" fn Java_com_paulscode_lightningfork_net_ArtiNative_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    state_dir: JString,
    cache_dir: JString,
) -> jint {
    // A panic must never unwind across the JNI boundary.
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        init_logging();
        let state: String = match env.get_string(&state_dir) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let cache: String = match env.get_string(&cache_dir) {
            Ok(s) => s.into(),
            Err(_) => return -1,
        };
        let mut running = match RUNNING.lock() {
            Ok(g) => g,
            Err(p) => p.into_inner(),
        };
        if let Some(port) = *running {
            return port as jint;
        }
        let listener = match StdTcpListener::bind((Ipv4Addr::LOCALHOST, 0)) {
            Ok(l) => l,
            Err(e) => {
                tracing::error!("SOCKS bind failed: {e}");
                return -1;
            }
        };
        let port = match listener.local_addr() {
            Ok(a) => a.port(),
            Err(_) => return -1,
        };
        if listener.set_nonblocking(true).is_err() {
            return -1;
        }
        *running = Some(port);
        PROGRESS.store(0, Ordering::SeqCst);
        runtime().spawn(async move {
            if let Err(e) = run(state, cache, listener).await {
                tracing::error!("Arti failed: {e:?}");
                PROGRESS.store(-1, Ordering::SeqCst);
                if let Ok(mut g) = RUNNING.lock() {
                    *g = None;
                }
            }
        });
        port as jint
    }))
    .unwrap_or(-1)
}

#[no_mangle]
pub extern "system" fn Java_com_paulscode_lightningfork_net_ArtiNative_nativeBootstrapPercent(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    PROGRESS.load(Ordering::SeqCst)
}

async fn run(state_dir: String, cache_dir: String, listener: StdTcpListener) -> Result<()> {
    let port = listener.local_addr()?.port();
    tracing::info!("nativeStart: port={port}");
    // rustls 0.23 requires a process-level CryptoProvider; Arti doesn't install
    // one, so do it here (idempotent) before any TLS is built.
    let _ = rustls::crypto::ring::default_provider().install_default();
    let mut builder = TorClientConfig::builder();
    builder
        .storage()
        .state_dir(CfgPath::new_literal(state_dir))
        .cache_dir(CfgPath::new_literal(cache_dir));
    let config = builder.build()?;

    let runtime = PreferredRuntime::current()?;
    let client = TorClient::with_runtime(runtime)
        .config(config)
        .create_unbootstrapped()?;

    // Report progress while bootstrapping.
    let watch = client.clone();
    tokio::spawn(async move {
        let mut last = -1;
        loop {
            let frac = watch.bootstrap_status().as_frac();
            let pct = (frac * 100.0) as i32;
            if pct != last {
                tracing::info!("bootstrap {pct}%");
                last = pct;
            }
            // Only ever up, and never to 100 from here: bootstrap() returning
            // is what sets 100, and a late store here must not undo it.
            if PROGRESS.load(Ordering::SeqCst) >= 0 {
                PROGRESS.fetch_max(pct.min(99), Ordering::SeqCst);
            }
            if frac >= 1.0 {
                break;
            }
            tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        }
    });

    client.bootstrap().await?;
    PROGRESS.store(100, Ordering::SeqCst);
    tracing::info!("Arti bootstrapped; SOCKS on 127.0.0.1:{port}");

    serve_socks(client, TcpListener::from_std(listener)?).await
}

async fn serve_socks(client: Arc<TorClient<PreferredRuntime>>, listener: TcpListener) -> Result<()> {
    loop {
        let (sock, _) = listener.accept().await?;
        let client = client.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(client, sock).await {
                tracing::warn!("socks conn: {e:?}");
            }
        });
    }
}

/// Minimal SOCKS5 CONNECT: no auth, IPv4/IPv6/domain, then splice to a Tor stream.
async fn handle(client: Arc<TorClient<PreferredRuntime>>, mut sock: TcpStream) -> Result<()> {
    // Greeting: version + method list; reply "no authentication".
    let mut head = [0u8; 2];
    sock.read_exact(&mut head).await?;
    if head[0] != 0x05 {
        return Err(anyhow!("not SOCKS5"));
    }
    let mut methods = vec![0u8; head[1] as usize];
    sock.read_exact(&mut methods).await?;
    sock.write_all(&[0x05, 0x00]).await?;

    // Request: ver, cmd, rsv, atyp.
    let mut req = [0u8; 4];
    sock.read_exact(&mut req).await?;
    if req[1] != 0x01 {
        // Only CONNECT is supported.
        sock.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
        return Ok(());
    }
    let host = match req[3] {
        0x01 => {
            let mut a = [0u8; 4];
            sock.read_exact(&mut a).await?;
            format!("{}.{}.{}.{}", a[0], a[1], a[2], a[3])
        }
        0x03 => {
            let mut len = [0u8; 1];
            sock.read_exact(&mut len).await?;
            let mut d = vec![0u8; len[0] as usize];
            sock.read_exact(&mut d).await?;
            String::from_utf8_lossy(&d).into_owned()
        }
        0x04 => {
            let mut a = [0u8; 16];
            sock.read_exact(&mut a).await?;
            std::net::Ipv6Addr::from(a).to_string()
        }
        _ => {
            sock.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            return Ok(());
        }
    };
    let mut p = [0u8; 2];
    sock.read_exact(&mut p).await?;
    let port = u16::from_be_bytes(p);

    match client.connect((host.as_str(), port)).await {
        Ok(mut stream) => {
            sock.write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            tokio::io::copy_bidirectional(&mut sock, &mut stream).await?;
            Ok(())
        }
        Err(e) => {
            sock.write_all(&[0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0]).await?;
            Err(anyhow!("tor connect failed: {e}"))
        }
    }
}
