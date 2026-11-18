//! JNI bridge between the Android app and the Rust OpenTunnel client.
//!
//! The Kotlin side only needs a small, poll-friendly surface: start (create or
//! resume an identity and connect), read the current status as JSON, disconnect
//! while keeping the identity, and forget the identity and all local files.
//!
//! Tunnel state lives in the app's private files directory, mirroring the
//! layout used by the OpenTunnel CLI:
//!
//! ```text
//! files/opentunnel/config/opentunnel/<profile>.toml
//! files/opentunnel/data/opentunnel/<profile>/{tunnel.json,token,certificate.pem,...}
//! files/opentunnel/state/opentunnel/<profile>/daemon.log
//! ```

use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jstring, JNI_FALSE, JNI_TRUE};
use jni::{Env, EnvUnowned, Outcome};
use opentunnel::protocol::names::is_valid_profile;
use opentunnel::{
    Client, ClientOptions, ProvisionStage, Route, Routes, State as TunnelState, Storage, Tunnel,
};
use serde::Serialize;
use tokio::runtime::Runtime;
use url::Url;

const DEFAULT_PROFILE: &str = "default";

#[derive(Debug, Clone)]
struct Config {
    files_dir: PathBuf,
    api_url: String,
    target: String,
    profile: String,
}

impl Config {
    fn new(
        files_dir: impl Into<PathBuf>,
        api_url: &str,
        target: &str,
        profile: &str,
    ) -> Result<Self, String> {
        let config = Self::paths_only(files_dir, api_url, profile)?;
        if config.api_url.is_empty() {
            return Err("invalid OpenTunnel server URL".to_owned());
        }
        let url = Url::parse(&config.api_url)
            .map_err(|error| format!("invalid OpenTunnel server URL: {error}"))?;
        if url.path() != "/" && !url.path().is_empty() {
            return Err("OpenTunnel server URL must not contain a path".to_owned());
        }
        if !target.trim().is_empty() {
            Route::new(target).validate("@")?;
        }
        Ok(Self {
            target: target.to_owned(),
            ..config
        })
    }

    /// Paths and profile only. Used when disassociating so invalid preferences
    /// can never prevent the local files from being deleted.
    fn paths_only(
        files_dir: impl Into<PathBuf>,
        api_url: &str,
        profile: &str,
    ) -> Result<Self, String> {
        let profile = if profile.trim().is_empty() {
            DEFAULT_PROFILE.to_owned()
        } else {
            profile.trim().to_owned()
        };
        if !is_valid_profile(&profile) {
            return Err(format!(
                "invalid profile '{profile}': use lowercase letters, numbers, and hyphens"
            ));
        }

        let api_url = match Url::parse(api_url.trim()) {
            Ok(mut url) => {
                if !matches!(url.scheme(), "http" | "https") {
                    return Err(
                        "OpenTunnel server URL must start with https:// or http://".to_owned()
                    );
                }
                url.set_query(None);
                url.set_fragment(None);
                url.to_string().trim_end_matches('/').to_owned()
            }
            Err(_) => String::new(),
        };

        Ok(Self {
            files_dir: files_dir.into(),
            api_url,
            target: String::new(),
            profile,
        })
    }

    fn base_dir(&self) -> PathBuf {
        self.files_dir.join("opentunnel")
    }

    fn storage_root(&self) -> PathBuf {
        // Storage::at(root) appends <profile>, matching
        // tunnel-data/data/opentunnel/<profile>.
        self.base_dir().join("data").join("opentunnel")
    }

    fn profile_dir(&self) -> PathBuf {
        self.storage_root().join(&self.profile)
    }

    fn route_config(&self) -> PathBuf {
        self.base_dir()
            .join("config")
            .join("opentunnel")
            .join(format!("{}.toml", self.profile))
    }

    fn state_dir(&self) -> PathBuf {
        self.base_dir()
            .join("state")
            .join("opentunnel")
            .join(&self.profile)
    }

    fn api_marker(&self) -> PathBuf {
        self.profile_dir().join("server")
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Status {
    registered: bool,
    has_files: bool,
    state: String,
    stage: Option<String>,
    url: Option<String>,
    hostname: Option<String>,
    profile: String,
    error: Option<String>,
    session: Option<String>,
    connections: usize,
}

impl Default for Status {
    fn default() -> Self {
        Self {
            registered: false,
            has_files: false,
            state: "idle".to_owned(),
            stage: None,
            url: None,
            hostname: None,
            profile: DEFAULT_PROFILE.to_owned(),
            error: None,
            session: None,
            connections: 0,
        }
    }
}

#[derive(Default)]
struct RuntimeState {
    generation: u64,
    config: Option<Config>,
    status: Status,
    tunnel: Option<Tunnel>,
}

fn state() -> &'static Mutex<RuntimeState> {
    static STATE: OnceLock<Mutex<RuntimeState>> = OnceLock::new();
    STATE.get_or_init(|| Mutex::new(RuntimeState::default()))
}

fn lock_state() -> MutexGuard<'static, RuntimeState> {
    state().lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn runtime() -> &'static Runtime {
    static RUNTIME: OnceLock<Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("opentunnel")
            .build()
            .expect("the OpenTunnel runtime can be created")
    })
}

fn now_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or_default()
}

fn log_state(config: &Config, status: &Status) {
    let dir = config.state_dir();
    if fs::create_dir_all(&dir).is_err() {
        return;
    }
    let Ok(mut file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join("daemon.log"))
    else {
        return;
    };
    let line = serde_json::to_string(status).unwrap_or_else(|_| "{}".to_owned());
    let _ = writeln!(file, "{} {}", now_secs(), line);
}

fn update_status(generation: u64, update: impl FnOnce(&mut Status)) {
    let (config, status) = {
        let mut state = lock_state();
        if state.generation != generation {
            return;
        }
        update(&mut state.status);
        (state.config.clone(), state.status.clone())
    };
    if let Some(config) = config {
        log_state(&config, &status);
    }
}

fn fail_status(message: impl Into<String>) {
    let message = message.into();
    let (config, status) = {
        let mut state = lock_state();
        state.status.state = "stopped".to_owned();
        state.status.stage = None;
        state.status.error = Some(message);
        (state.config.clone(), state.status.clone())
    };
    if let Some(config) = config {
        log_state(&config, &status);
    }
}

fn disconnect_locked(state: &mut RuntimeState) {
    let tunnel = state.tunnel.take();
    drop(tunnel);
    state.status.state = "idle".to_owned();
    state.status.stage = None;
    state.status.session = None;
    state.status.connections = 0;
    state.status.error = None;
}

fn set_identity(status: &mut Status, hostname: &str) {
    status.registered = true;
    status.hostname = Some(hostname.to_owned());
    status.url = Some(format!("https://{hostname}"));
}

fn write_route_config(config: &Config) -> Result<(), String> {
    let path = config.route_config();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("creating {}: {error}", parent.display()))?;
    }
    let target = serde_json::to_string(&config.target).unwrap_or_else(|_| "\"\"".to_owned());
    let body = format!("[routes]\n\"@\" = {target}\n");
    fs::write(&path, body).map_err(|error| format!("writing {}: {error}", path.display()))
}

fn read_api_marker(config: &Config) -> Option<String> {
    fs::read_to_string(config.api_marker())
        .ok()
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
}

fn write_api_marker(config: &Config) -> Result<(), String> {
    let path = config.api_marker();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("creating {}: {error}", parent.display()))?;
    }
    fs::write(&path, format!("{}\n", config.api_url))
        .map_err(|error| format!("writing {}: {error}", path.display()))
}

fn build_client(config: &Config) -> Result<Client, String> {
    let api = Url::parse(&config.api_url)
        .map_err(|error| format!("invalid OpenTunnel server URL: {error}"))?;
    Ok(Client::new(ClientOptions {
        api: Some(api),
        storage: Some(Storage::at(config.storage_root())),
        ca_cert: None,
    }))
}

fn stage_key(stage: ProvisionStage) -> &'static str {
    match stage {
        ProvisionStage::CreatingTunnel => "creating_tunnel",
        ProvisionStage::GeneratingKey | ProvisionStage::GeneratingCsr => "generating_certificate",
        ProvisionStage::ResumingCertificate => "resuming_certificate",
        ProvisionStage::RequestingCertificate => "requesting_certificate",
        ProvisionStage::WaitingCertificate => "waiting_certificate",
        ProvisionStage::SavingIdentity => "saving_identity",
        ProvisionStage::Ready => "ready",
    }
}

fn stage_status(generation: u64, stage: ProvisionStage) {
    update_status(generation, |status| {
        status.state = "provisioning".to_owned();
        status.stage = Some(stage_key(stage).to_owned());
        status.error = None;
    });
}

fn apply_tunnel_status(status: &mut Status, tunnel: &Tunnel) {
    let current = tunnel.status();
    status.state = match current.state {
        TunnelState::WaitingRoutes => "waiting_routes".to_owned(),
        TunnelState::Connecting => "connecting".to_owned(),
        TunnelState::Connected => "connected".to_owned(),
        TunnelState::Reconnecting => "reconnecting".to_owned(),
        TunnelState::Stopped => "stopped".to_owned(),
    };
    status.hostname = Some(current.hostname.clone());
    status.url = Some(format!("https://{}", current.hostname));
    status.session = current.session;
    status.connections = current.connections;
    if let Some(error) = current.last_error {
        status.error = Some(error);
    } else if current.state == TunnelState::Connected {
        status.error = None;
    }
}

async fn start_inner(config: Config, generation: u64) -> Result<(), String> {
    write_route_config(&config)?;
    let client = build_client(&config)?;

    let existing = client
        .get(&config.profile)
        .map_err(|error| error.to_string())?;

    if existing.is_some() {
        if let Some(stored_api) = read_api_marker(&config) {
            if stored_api != config.api_url {
                return Err(format!(
                    "This tunnel is registered with {stored_api}. Use \"Disconnect & disassociate\" before switching to {}.",
                    config.api_url
                ));
            }
        }
        let existing = existing.expect("checked above");
        let certificate_valid = humantime::parse_rfc3339_weak(&existing.certificate_expiry)
            .map(|expiry| expiry > SystemTime::now())
            .unwrap_or(false);
        update_status(generation, |status| {
            set_identity(status, &existing.hostname);
            status.state = "provisioning".to_owned();
            status.stage = Some(if certificate_valid {
                "connecting".to_owned()
            } else {
                "checking".to_owned()
            });
            status.error = None;
        });

        if certificate_valid {
            // The saved certificate still serves. Connecting immediately keeps the
            // tunnel usable offline; the background refresh picks up renewals.
            write_api_marker(&config)?;
            update_status(generation, |status| {
                set_identity(status, &existing.hostname);
                status.state = "provisioning".to_owned();
                status.stage = Some("connecting".to_owned());
                status.error = None;
            });
        } else {
            let identity = client
                .ensure(&config.profile)
                .await
                .map_err(|error| error.to_string())?;
            write_api_marker(&config)?;
            update_status(generation, |status| {
                set_identity(status, &identity.hostname);
                status.state = "provisioning".to_owned();
                status.stage = Some("connecting".to_owned());
                status.error = None;
            });
        }
    } else {
        update_status(generation, |status| {
            status.registered = false;
            status.state = "provisioning".to_owned();
            status.stage = Some("creating_tunnel".to_owned());
            status.error = None;
        });

        let identity = client
            .create(&config.profile, |stage| stage_status(generation, stage))
            .await
            .map_err(|error| error.to_string())?;
        write_api_marker(&config)?;
        update_status(generation, |status| {
            set_identity(status, &identity.hostname);
            status.state = "provisioning".to_owned();
            status.stage = Some("connecting".to_owned());
            status.error = None;
        });
    }

    let identity = client
        .get(&config.profile)
        .map_err(|error| error.to_string())?
        .ok_or_else(|| "the tunnel identity was not saved".to_owned())?;

    let mut routes: Routes = BTreeMap::new();
    routes.insert("@".to_owned(), Route::new(config.target.clone()));
    let tunnel = client
        .connect(&config.profile, routes)
        .map_err(|error| error.to_string())?;

    update_status(generation, |status| {
        set_identity(status, &identity.hostname);
        status.state = "connecting".to_owned();
        status.stage = None;
        status.error = None;
    });

    let mut state = lock_state();
    if state.generation == generation {
        state.tunnel = Some(tunnel);
        state.status.registered = true;
        state.status.hostname = Some(identity.hostname.clone());
        state.status.url = Some(format!("https://{}", identity.hostname));
    }
    Ok(())
}

fn spawn_start(config: Config, generation: u64) {
    runtime().spawn(async move {
        if let Err(error) = start_inner(config, generation).await {
            update_status(generation, |status| {
                status.state = "stopped".to_owned();
                status.stage = None;
                status.error = Some(error);
            });
        }
    });
}

fn remove_profile_files(config: &Config) {
    let _ = fs::remove_dir_all(config.profile_dir());
    let _ = fs::remove_file(config.route_config());
    let _ = fs::remove_dir_all(config.state_dir());
}

fn spawn_forget(config: Config, generation: u64) {
    // Capture the credentials before deleting anything so the server-side tunnel can
    // still be removed in the background.
    let identity = Storage::at(config.storage_root())
        .load(&config.profile)
        .ok()
        .flatten();

    // Delete local files immediately: "Disconnect & disassociate" must not depend on
    // the network or on the process staying alive.
    remove_profile_files(&config);
    update_status(generation, |status| {
        status.registered = false;
        status.has_files = false;
        status.state = "idle".to_owned();
        status.stage = None;
        status.url = None;
        status.hostname = None;
        status.session = None;
        status.connections = 0;
        status.error = None;
    });
    let _ = fs::remove_dir_all(config.state_dir());

    // Then tell the server, best-effort. Local state is already gone.
    runtime().spawn(async move {
        let Some(identity) = identity else {
            return;
        };
        let Ok(client) = build_client(&config) else {
            return;
        };
        let _ = tokio::time::timeout(
            Duration::from_secs(30),
            client.api().remove(&identity.token, &identity.id),
        )
        .await;
    });
}

fn read_string(env: &Env<'_>, value: &JString<'_>, name: &str) -> Result<String, String> {
    value
        .try_to_string(env)
        .map_err(|error| format!("reading {name}: {error}"))
}

fn status_for(files_dir: &str, profile: &str) -> Status {
    let state = lock_state();
    let matching = state
        .config
        .as_ref()
        .is_some_and(|config| {
            config.files_dir.as_path() == Path::new(files_dir) && config.profile == profile
        });
    // Before the first successful start there is no config to match; still surface a
    // synchronous argument error from nativeStart/nativeForget.
    let show_cached_error = state.config.is_none() && state.status.error.is_some();

    let mut status = if matching || show_cached_error {
        state.status.clone()
    } else {
        Status {
            profile: profile.to_owned(),
            ..Status::default()
        }
    };

    // The files are the source of truth for registration, so a status poll
    // right after provisioning finishes (or after files were removed) is
    // correct even before the background task updates the cached state.
    let storage_root = Path::new(files_dir)
        .join("opentunnel")
        .join("data")
        .join("opentunnel");
    status.has_files = storage_root.join(profile).exists();
    if let Ok(Some(identity)) = Storage::at(storage_root).load(profile) {
        set_identity(&mut status, &identity.hostname);
    } else {
        status.registered = false;
        status.url = None;
        status.hostname = None;
    }

    if matching {
        if let Some(tunnel) = &state.tunnel {
            apply_tunnel_status(&mut status, tunnel);
        }
    } else if status.state == "provisioning" || status.state == "forgetting" {
        status.state = "idle".to_owned();
    }

    status
}

fn status_json(files_dir: &str, profile: &str) -> String {
    serde_json::to_string(&status_for(files_dir, profile))
        .unwrap_or_else(|_| "{\"registered\":false,\"state\":\"idle\"}".to_owned())
}

fn to_jstring(env: &mut Env<'_>, value: String) -> jstring {
    match env.new_string(value) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_digitallyrefined_androidipcamera_opentunnel_OpenTunnelNative_nativeStart(
    mut unowned_env: EnvUnowned<'_>,
    _this: JObject<'_>,
    files_dir: JString<'_>,
    api_url: JString<'_>,
    target: JString<'_>,
    profile: JString<'_>,
) -> jboolean {
    let outcome = unowned_env.with_env(|env| -> Result<jboolean, jni::errors::Error> {
        let result = (|| -> Result<(), String> {
            let files_dir = read_string(env, &files_dir, "files directory")?;
            let api_url = read_string(env, &api_url, "server URL")?;
            let target = read_string(env, &target, "target")?;
            let profile = read_string(env, &profile, "profile")?;
            let config = Config::new(files_dir, &api_url, &target, &profile)?;

            let generation = {
                let mut state = lock_state();
                state.generation = state.generation.wrapping_add(1);
                let generation = state.generation;
                let tunnel = state.tunnel.take();
                drop(tunnel);
                state.status = Status {
                    profile: config.profile.clone(),
                    state: "provisioning".to_owned(),
                    stage: Some("starting".to_owned()),
                    ..Status::default()
                };
                state.config = Some(config.clone());
                generation
            };
            spawn_start(config, generation);
            Ok(())
        })();

        match result {
            Ok(()) => Ok(JNI_TRUE),
            Err(error) => {
                fail_status(error);
                Ok(JNI_FALSE)
            }
        }
    });

    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(error) => {
            fail_status(error.to_string());
            JNI_FALSE
        }
        Outcome::Panic(_) => {
            fail_status("starting the tunnel panicked");
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_digitallyrefined_androidipcamera_opentunnel_OpenTunnelNative_nativeStatus(
    mut unowned_env: EnvUnowned<'_>,
    _this: JObject<'_>,
    files_dir: JString<'_>,
    profile: JString<'_>,
) -> jstring {
    let outcome = unowned_env.with_env(|env| -> Result<jstring, jni::errors::Error> {
        let result = (|| -> Result<String, String> {
            let files_dir = read_string(env, &files_dir, "files directory")?;
            let profile = read_string(env, &profile, "profile")?;
            let profile = if profile.trim().is_empty() {
                DEFAULT_PROFILE.to_owned()
            } else {
                profile
            };
            Ok(status_json(&files_dir, &profile))
        })();

        let json = result.unwrap_or_else(|error| {
            serde_json::json!({
                "registered": false,
                "state": "stopped",
                "error": error,
            })
            .to_string()
        });
        Ok(to_jstring(env, json))
    });

    match outcome.into_outcome() {
        Outcome::Ok(value) => value,
        Outcome::Err(_) | Outcome::Panic(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_github_digitallyrefined_androidipcamera_opentunnel_OpenTunnelNative_nativeDisconnect(
    _env: EnvUnowned<'_>,
    _this: JObject<'_>,
) {
    let mut state = lock_state();
    state.generation = state.generation.wrapping_add(1);
    disconnect_locked(&mut state);
}

#[no_mangle]
pub extern "system" fn Java_com_github_digitallyrefined_androidipcamera_opentunnel_OpenTunnelNative_nativeForget(
    mut unowned_env: EnvUnowned<'_>,
    _this: JObject<'_>,
    files_dir: JString<'_>,
    api_url: JString<'_>,
    profile: JString<'_>,
) {
    let outcome = unowned_env.with_env(|env| -> Result<(), jni::errors::Error> {
        let result = (|| -> Result<(), String> {
            let files_dir = read_string(env, &files_dir, "files directory")?;
            let api_url = read_string(env, &api_url, "server URL")?;
            let profile = read_string(env, &profile, "profile")?;
            let config = Config::paths_only(files_dir, &api_url, &profile)?;

            let generation = {
                let mut state = lock_state();
                state.generation = state.generation.wrapping_add(1);
                let generation = state.generation;
                let tunnel = state.tunnel.take();
                drop(tunnel);
                state.status = Status {
                    profile: config.profile.clone(),
                    state: "forgetting".to_owned(),
                    stage: None,
                    ..Status::default()
                };
                state.config = Some(config.clone());
                generation
            };
            spawn_forget(config, generation);
            Ok(())
        })();

        if let Err(error) = result {
            fail_status(error);
        }
        Ok(())
    });

    match outcome.into_outcome() {
        Outcome::Ok(()) => {}
        Outcome::Err(error) => fail_status(error.to_string()),
        Outcome::Panic(_) => fail_status("forgetting the tunnel panicked"),
    }
}
