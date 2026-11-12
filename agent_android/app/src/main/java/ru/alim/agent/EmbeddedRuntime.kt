package ru.alim.agent

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Self-contained Linux runtime for Codex.
 *
 * The APK carries a Termux bootstrap and the Python/Git packages needed by the
 * local bridge.  Codex itself is a static aarch64 Linux binary and is fetched
 * once on first launch.  Everything lives in the app's private storage, so the
 * user never has to install Termux, type a local token, or start a server.
 */
object EmbeddedRuntime {
    private const val TAG = "EmbeddedRuntime"
    const val PORT = 8766
    private const val CODEX_VERSION = "0.167.2"
    private const val CODEX_URL =
        "https://github.com/openai/codex/releases/download/rust-v$CODEX_VERSION/codex-aarch64-unknown-linux-musl.tar.gz"
    private const val CODEX_SHA256 = "ed81fa2c7d209689af061b6f305ed554218de92a1c2c4019142b28f66fa64add"

    enum class State { OFF, PREPARING, DOWNLOADING, STARTING, READY, ERROR }

    var state by mutableStateOf(State.OFF)
        private set
    var detail by mutableStateOf("Локальное окружение не запущено")
        private set
    var progress by mutableFloatStateOf(0f)
        private set

    @Volatile private var lastLog = ""
    @Volatile private var process: Process? = null
    @Volatile private var worker: Thread? = null
    private val gate = Any()

    data class Paths(
        val filesDir: String,
        val prefix: String,
        val home: String,
        val tmp: String,
        val tenHome: String,
    )

    fun paths(ctx: Context): Paths {
        val files = ctx.applicationContext.filesDir.absolutePath
        val home = "$files/home"
        return Paths(
            filesDir = files,
            prefix = "$files/usr",
            home = home,
            tmp = "$files/usr/tmp",
            tenHome = "$home/.ten-local",
        )
    }

    fun logTail(): String = lastLog.takeLast(1500)

    fun tokenOrEmpty(ctx: Context): String {
        val token = File(paths(ctx).tenHome, "token")
        if (!token.isFile) return ""
        return runCatching { token.readText().trim() }.getOrDefault("")
            .takeIf { it.length >= 32 }.orEmpty()
    }

    fun isReady(ctx: Context): Boolean = tokenOrEmpty(ctx).isNotBlank() && healthy()

    fun startAsync(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(gate) {
            if (worker?.isAlive == true) return
            if (state == State.READY && healthy()) return
            worker = Thread({ prepare(app) }, "ten-runtime").also { it.start() }
        }
    }

    fun restart(ctx: Context) {
        val app = ctx.applicationContext
        stop()
        startAsync(app)
    }

    fun stop() {
        process?.let { proc ->
            runCatching { proc.destroy() }
            runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
            if (runCatching { proc.isAlive }.getOrDefault(false)) runCatching { proc.destroyForcibly() }
        }
        process = null
        state = State.OFF
        detail = "Локальное окружение остановлено"
        progress = 0f
    }

    fun awaitToken(ctx: Context, timeoutMs: Long = 15 * 60_000): String {
        startAsync(ctx)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (state == State.ERROR) error(detail)
            val token = tokenOrEmpty(ctx)
            if (token.isNotBlank() && healthy()) return token
            if (state == State.READY && !healthy()) {
                state = State.STARTING
                detail = "Перезапускаю локальный сервер…"
                runCatching { startServer(ctx) }
            }
            Thread.sleep(700)
        }
        error("Локальное окружение ещё готовится: $detail")
    }

    private fun prepare(ctx: Context) {
        try {
            state = State.PREPARING
            detail = "Проверяю локальное окружение…"
            progress = 0.02f
            val p = paths(ctx)
            File(p.home).mkdirs()
            File(p.prefix, "tmp").mkdirs()
            File(p.tenHome).mkdirs()

            if (!File(p.prefix, "bin/bash").exists()) {
                detail = "Распаковываю Linux-окружение…"
                progress = 0.05f
                extractBootstrap(ctx, p)
            }
            if (!File(p.prefix, "bin/python3").exists()) {
                detail = "Устанавливаю Python и Git…"
                progress = 0.25f
                installBundledPackages(ctx, p)
            }
            if (!validCodex(p)) {
                state = State.DOWNLOADING
                detail = "Скачиваю Codex для arm64 (~110 МБ)…"
                progress = 0.35f
                downloadCodex(p)
            }
            writeServer(ctx, p)
            writeConfig(p)
            installDns(p)
            ensureToken(p)

            if (healthy()) {
                state = State.READY
                detail = "Готово · локальный Codex работает"
                progress = 1f
                return
            }
            state = State.STARTING
            detail = "Запускаю локальный сервер…"
            progress = 0.9f
            startServer(ctx)
            if (!waitHealthy(45_000)) {
                val log = lastLog.takeLast(1200)
                error(if (log.isBlank()) "Сервер не ответил" else "Сервер не ответил: $log")
            }
            state = State.READY
            detail = "Готово · локальный Codex работает"
            progress = 1f
        } catch (e: Exception) {
            Log.e(TAG, "Runtime preparation failed", e)
            state = State.ERROR
            detail = e.message?.take(800) ?: "Не удалось подготовить окружение"
            progress = 0f
        } finally {
            synchronized(gate) { if (worker === Thread.currentThread()) worker = null }
        }
    }

    private fun extractBootstrap(ctx: Context, p: Paths) {
        val assetName = "bootstrap-aarch64.zip"
        val staging = File(p.filesDir, "usr-staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val symlinks = ArrayList<Pair<String, String>>()
        val buffer = ByteArray(64 * 1024)
        ctx.assets.open(assetName).use { asset ->
            ZipInputStream(asset).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "SYMLINKS.txt") {
                        BufferedReader(InputStreamReader(zip)).useLines { lines ->
                            lines.forEach { line ->
                                val parts = line.split('←')
                                if (parts.size == 2) {
                                    val target = parts[0].replace(termuxPrefix, p.prefix)
                                    val link = File(staging, parts[1])
                                    link.parentFile?.mkdirs()
                                    symlinks += target to link.absolutePath
                                }
                            }
                        }
                    } else {
                        val target = File(staging, name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                var read = zip.read(buffer)
                                while (read != -1) {
                                    out.write(buffer, 0, read)
                                    read = zip.read(buffer)
                                }
                            }
                            if (executable(name)) runCatching { Os.chmod(target.absolutePath, 0x1C0) }
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (symlinks.isEmpty()) error("В bootstrap нет SYMLINKS.txt")
        for ((target, link) in symlinks) {
            runCatching {
                val file = File(link)
                if (file.exists() || java.nio.file.Files.isSymbolicLink(file.toPath())) file.delete()
                Os.symlink(target, link)
            }
        }
        val prefix = File(p.prefix)
        if (prefix.exists()) prefix.deleteRecursively()
        if (!staging.renameTo(prefix)) error("Не удалось переместить Linux-окружение")
        File(p.home).mkdirs()
        File(p.tmp).mkdirs()
        fixAptConfig(p)
        rewriteAbsoluteSymlinks(p)
        rewriteShebangs(p)
    }

    private fun installBundledPackages(ctx: Context, p: Paths) {
        val debs = File(p.prefix, "tmp/bundled-debs")
        val stage = File(p.prefix, "tmp/deb-stage")
        debs.deleteRecursively()
        stage.deleteRecursively()
        debs.mkdirs()
        val names = ctx.assets.list("runtime/debs").orEmpty()
        if (names.isEmpty()) error("В APK нет runtime/debs — пересобери APK через CI")
        for (name in names) {
            if (!name.endsWith(".deb")) continue
            ctx.assets.open("runtime/debs/$name").use { input ->
                File(debs, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        val script = """
            set -e
            cd ${q(debs.absolutePath)}
            for deb in *.deb; do
              [ -e "${'$'}deb" ] || continue
              dpkg-deb -x "${'$'}deb" ${q(stage.absolutePath)}
            done
            src=${q(File(stage, "data/data/com.termux/files/usr").absolutePath)}
            if [ -d "${'$'}src" ]; then cp -a "${'$'}src"/. ${q(p.prefix)}/; fi
            chmod 700 ${q(p.prefix)}/bin/python* ${q(p.prefix)}/bin/git ${q(p.prefix)}/bin/rg 2>/dev/null || true
            if [ ! -e ${q(p.prefix)}/bin/python ]; then ln -s python3 ${q(p.prefix)}/bin/python; fi
            rm -rf ${q(stage.absolutePath)} ${q(debs.absolutePath)}
        """.trimIndent()
        runShell(p, script, 300)
        if (!File(p.prefix, "bin/python3").exists()) error("Python не установился")
        rewriteAbsoluteSymlinks(p)
        rewriteShebangs(p)
    }

    /** Termux packages contain absolute symlinks to /data/data/com.termux/files/usr. */
    private fun rewriteAbsoluteSymlinks(p: Paths) {
        val termuxPrefix = "/data/data/com.termux/files/usr"
        val root = File(p.prefix)
        if (!root.isDirectory) return
        root.walkTopDown().forEach { file ->
            if (!runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)) return@forEach
            runCatching {
                val target = java.nio.file.Files.readSymbolicLink(file.toPath()).toString()
                if (!target.startsWith(termuxPrefix)) return@runCatching
                val relocated = p.prefix + target.removePrefix(termuxPrefix)
                file.delete()
                Os.symlink(relocated, file.absolutePath)
            }
        }
    }

    /** Termux packages embed /data/data/com.termux/files/usr in script shebangs. */
    private fun rewriteShebangs(p: Paths) {
        val termuxPrefix = "/data/data/com.termux/files/usr"
        for (root in listOf(File(p.prefix, "bin"), File(p.prefix, "libexec"))) {
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { file ->
                if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.length() > 2_000_000) return@forEach
                val head = ByteArray(2)
                val ok = runCatching {
                    file.inputStream().use { input -> input.read(head) == 2 && head[0] == '#'.code.toByte() && head[1] == '!'.code.toByte() }
                }.getOrDefault(false)
                if (!ok) return@forEach
                runCatching {
                    val text = file.readText()
                    if (text.contains(termuxPrefix)) file.writeText(text.replace(termuxPrefix, p.prefix))
                }
            }
        }
    }

    private fun validCodex(p: Paths): Boolean {
        val codex = File(p.prefix, "bin/codex")
        return codex.isFile && codex.length() > 50_000_000
    }

    private fun downloadCodex(p: Paths) {
        val dir = File(p.prefix, "tmp/codex-download")
        dir.deleteRecursively()
        dir.mkdirs()
        val archive = File(dir, "codex.tar.gz")
        val script = """
            set -e
            cd ${q(dir.absolutePath)}
            curl -fL --retry 3 --connect-timeout 20 --max-time 1800 -o codex.tar.gz ${q(CODEX_URL)}
            sum="${'$'}(sha256sum codex.tar.gz | cut -d' ' -f1)"
            [ "${'$'}sum" = ${q(CODEX_SHA256)} ] || { echo "SHA256 mismatch: ${'$'}sum"; exit 12; }
            tar xzf codex.tar.gz
            [ -f codex-aarch64-unknown-linux-musl ] || { echo "codex binary missing"; exit 13; }
            mv codex-aarch64-unknown-linux-musl ${q(File(p.prefix, "bin/codex").absolutePath)}
            chmod 700 ${q(File(p.prefix, "bin/codex").absolutePath)}
            rm -rf ${q(dir.absolutePath)}
        """.trimIndent()
        runShell(p, script, 1900)
        if (!validCodex(p)) error("Codex не установился")
        progress = 0.75f
    }

    private fun writeServer(ctx: Context, p: Paths) {
        val dir = File(p.home, "ten_runtime")
        dir.mkdirs()
        val target = File(dir, "server.py")
        ctx.assets.open("runtime/server.py").use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun writeConfig(p: Paths) {
        val dir = File(p.home, ".codex")
        dir.mkdirs()
        val workspace = File(p.tenHome, "workspace").absolutePath
        File(dir, "config.toml").writeText(
            """
            approval_policy = "never"
            sandbox_mode = "danger-full-access"
            """.trimIndent() + "\n"
        )
        File(p.tenHome, "workspace").mkdirs()
        File(p.home, "workspace").mkdirs()
        // Keep a likely repository location ready for git clone.
        File(workspace).mkdirs()
    }

    private fun installDns(p: Paths) {
        runCatching {
            val cm = Agent.app.getSystemService(ConnectivityManager::class.java) ?: return
            val link = cm.getLinkProperties(cm.activeNetwork) ?: return
            val dns = link.dnsServers.mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() && !it.contains(':') }
                .distinct()
            if (dns.isEmpty()) return
            File(p.prefix, "etc/resolv.conf").writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
        }
    }

    private fun ensureToken(p: Paths): String {
        val token = File(p.tenHome, "token")
        if (token.isFile) {
            val value = token.readText().trim()
            if (value.length >= 32) return value
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        token.writeText(value)
        runCatching { Os.chmod(token.absolutePath, 0x180) }
        return value
    }

    private fun startServer(ctx: Context) {
        val p = paths(ctx)
        if (healthy()) return
        process?.let { old ->
            if (runCatching { old.isAlive }.getOrDefault(false)) return
        }
        val script = File(p.home, "ten_runtime/server.py")
        if (!script.isFile) error("Нет server.py")
        val pb = ProcessBuilder(File(p.prefix, "bin/python3").absolutePath, script.absolutePath)
        pb.environment().clear()
        pb.environment().putAll(environment(p))
        pb.directory(File(p.home, "ten_runtime"))
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc
        Thread({
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        lastLog = (lastLog + "\n" + line).takeLast(4000)
                        Log.d(TAG, line)
                    }
                }
            }
            if (state == State.READY || state == State.STARTING) {
                state = State.ERROR
                detail = "Локальный сервер остановился: " + lastLog.takeLast(500)
            }
        }, "ten-runtime-log").start()
    }

    private fun waitHealthy(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (healthy()) return true
            val proc = process
            if (proc != null && !runCatching { proc.isAlive }.getOrDefault(false)) return false
            Thread.sleep(500)
        }
        return false
    }

    private fun healthy(): Boolean = runCatching {
        val url = URL("http://127.0.0.1:$PORT/health")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 1200
        connection.readTimeout = 1800
        connection.requestMethod = "GET"
        val code = connection.responseCode
        connection.disconnect()
        code == 200
    }.getOrDefault(false)

    private fun runShell(p: Paths, script: String, timeoutSeconds: Long) {
        val pb = ProcessBuilder(File(p.prefix, "bin/bash").absolutePath, "-c", script)
        pb.environment().clear()
        pb.environment().putAll(environment(p))
        pb.directory(File(p.home))
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                BufferedReader(InputStreamReader(proc.inputStream)).useLines { lines ->
                    lines.forEach { line ->
                        output.appendLine(line)
                        lastLog = (lastLog + "\n" + line).takeLast(4000)
                        Log.d(TAG, line)
                    }
                }
            }
        }
        reader.start()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            runCatching { proc.destroyForcibly() }
            reader.join(1000)
            error("Команда окружения не завершилась за $timeoutSeconds сек.")
        }
        reader.join(1500)
        if (proc.exitValue() != 0) {
            error("Окружение: " + output.takeLast(900).trim().ifBlank { "код ${proc.exitValue()}" })
        }
    }

    private fun environment(p: Paths): Map<String, String> {
        val preload = File(p.prefix, "lib/libtermux-exec.so")
        val env = linkedMapOf(
            "PREFIX" to p.prefix,
            "HOME" to p.home,
            "PATH" to "${p.prefix}/bin:${p.prefix}/bin/applets:/system/bin:/system/xbin",
            "SHELL" to "${p.prefix}/bin/bash",
            "LD_LIBRARY_PATH" to "${p.prefix}/lib",
            "TERMUX_PREFIX" to p.prefix,
            "TERMUX__PREFIX" to p.prefix,
            "LANG" to "en_US.UTF-8",
            "TMPDIR" to p.tmp,
            "TERM" to "xterm-256color",
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "SSL_CERT_FILE" to "${p.prefix}/etc/tls/cert.pem",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
            "CURL_CA_BUNDLE" to "${p.prefix}/etc/tls/cert.pem",
            "GIT_SSL_CAINFO" to "${p.prefix}/etc/tls/cert.pem",
            "OPENSSL_CONF" to "${p.prefix}/etc/tls/openssl.cnf",
            "GIT_TERMINAL_PROMPT" to "0",
            "GIT_EXEC_PATH" to "${p.prefix}/libexec/git-core",
            "GIT_TEMPLATE_DIR" to "${p.prefix}/share/git-core/templates",
            "GIT_CONFIG_SYSTEM" to "${p.prefix}/etc/gitconfig",
            "PYTHONHOME" to p.prefix,
            "PYTHONUTF8" to "1",
            "PYTHONNOUSERSITE" to "1",
            "TEN_LOCAL_HOME" to p.tenHome,
            "TEN_CODEX_SANDBOX" to "danger-full-access",
        )
        if (preload.exists()) env["LD_PRELOAD"] = preload.absolutePath
        return env
    }

    private fun fixAptConfig(p: Paths) {
        runCatching {
            val prefix = p.prefix
            File(prefix, "etc/apt").mkdirs()
            File(prefix, "etc/apt/apt.conf").writeText(
                """
                Dir "/";
                Dir::State "$prefix/var/lib/apt/";
                Dir::State::status "$prefix/var/lib/dpkg/status";
                Dir::Cache "$prefix/var/cache/apt/";
                Dir::Log "$prefix/var/log/apt/";
                Dir::Etc "$prefix/etc/apt/";
                Dir::Etc::SourceList "$prefix/etc/apt/sources.list";
                Dir::Etc::SourceParts "";
                Dir::Bin::dpkg "$prefix/bin/dpkg";
                Dir::Bin::Methods "$prefix/lib/apt/methods/";
                Dir::Bin::apt-key "$prefix/bin/apt-key";
                Dpkg::Options:: "--force-configure-any";
                Dpkg::Options:: "--force-bad-path";
                Dpkg::Options:: "--instdir=$prefix";
                Acquire::AllowInsecureRepositories "true";
                """.trimIndent() + "\n"
            )
            File(prefix, "var/log/apt").mkdirs()
            val sources = File(prefix, "etc/apt/sources.list")
            if (sources.exists()) {
                sources.writeText(sources.readText().replace("https://", "http://"))
            }
        }
    }

    private fun executable(name: String): Boolean =
        name.startsWith("bin/") || name.startsWith("libexec/") || name.startsWith("lib/apt/methods/") ||
            name.startsWith("lib/bash/") || name.endsWith(".so") || name.contains("/bin/")

    private fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
