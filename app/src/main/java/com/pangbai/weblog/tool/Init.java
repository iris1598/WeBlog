package com.pangbai.weblog.tool;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;

import com.pangbai.weblog.R;
import com.pangbai.weblog.execute.cmdExer;
import com.pangbai.weblog.preference.PrefManager;
import com.pangbai.weblog.project.ProjectManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver;

public class Init {

    public static final String TAG = "WeBlogInit";

    public static String filesDirPath;


    //  public static String shellPath;
    public static String libDir, binDir, nodeDir, weblogDir;

    public static String busyboxPath, shPath, scriptsPath;
    public static String[] envp;
    final public static String sdcardPath = "/storage/emulated/0/";
    final public static String linker = "/system/bin/linker64";
    public static String keyPath;

    /**
     * APK 的 native 库目录，例如 /data/app/~~xxx/com.pangbai.weblog-yyy/lib/arm64。
     * <p>
     * Android 10（API 29）起，应用私有目录 /data/data/<pkg>/files 下的文件不再拥有
     * execute / execute_no_trans 权限，直接 execve 会返回 EACCES（Permission denied）；
     * 而 apk_data_file（也就是 nativeLibraryDir 下的 lib*.so）仍然带有 x_file_perms，
     * 可以直接被 execve。所以 node、busybox 这类需要被反复 exec 的程序放在这里。
     */
    public static String nativeLibDir;
    /** nativeLibraryDir/libnode.so：node 真正的可执行文件（来自 jniLibs） */
    public static String nodeNativePath;
    /** nativeLibraryDir/libbusybox.so：busybox 的副本，用来启动 shell */
    public static String busyboxNativePath;
    /** nativeLibraryDir 里的 git 主程序（对应 usr/libexec/git-core/git） */
    public static String gitNativePath;
    /** nativeLibraryDir 里的 git-remote-http，https 协议的 clone / push 靠它 */
    public static String gitRemoteHttpNativePath;
    /** nativeLibraryDir 里的 ssh，git 走 ssh 协议时要用 */
    public static String sshNativePath;

    final public static boolean android10 = Build.VERSION.SDK_INT >= 29;
    boolean result;


    AlertDialog dialog;
    public static String systemShell = "/system/bin/sh";

    public Init(Activity ct) {

        File files = ct.getFilesDir();


        filesDirPath = files.getAbsolutePath();
        libDir = filesDirPath + "/usr/lib";
        binDir = filesDirPath + "/usr/bin";
        // busyboxPath =  ct.getApplicationInfo().nativeLibraryDir + "/busybox";
        busyboxPath = binDir + "/busybox";
        shPath = binDir + "/sh";
        nodeDir = binDir + "/node";
        weblogDir = filesDirPath + "/weblog";
        keyPath = filesDirPath + "/weblog/keys";
        scriptsPath = filesDirPath + "/script/";

        nativeLibDir = ct.getApplicationInfo().nativeLibraryDir;
        nodeNativePath = nativeLibDir + "/libnode.so";
        busyboxNativePath = nativeLibDir + "/libbusybox.so";
        gitNativePath = nativeLibDir + "/libgit.so";
        gitRemoteHttpNativePath = nativeLibDir + "/libgit-remote-http.so";
        sshNativePath = nativeLibDir + "/libssh.so";

        String[] envp = {
                //"PATH=" + "/system/bin"
                "PATH=" + Init.binDir + ":/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/vendor/bin",
                "LD_PRELOAD=" + Init.libDir + "/libexec.so",
                "HOME=" + Init.filesDirPath,
                "TMPDIR=" + Init.filesDirPath + "/usr/tmp",
                "PREFIX=" + Init.filesDirPath + "/usr",
                "LD_LIBRARY_PATH=" + Init.filesDirPath + "/usr/lib" + ":" + Init.nativeLibDir,
                // node 被软链到 nativeLibraryDir 后 execPath 不再是 $PREFIX/bin/node，
                // 显式给出全局模块目录，保证 npm/hexo 的全局包仍然找得到
                "NODE_PATH=" + Init.filesDirPath + "/usr/lib/node_modules",
                // git 被搬到 nativeLibraryDir 后，自身位置不再是 $PREFIX/bin/git，
                // 必须显式告诉它 helper（git-remote-https 等）和模板在哪
                "GIT_EXEC_PATH=" + Init.filesDirPath + "/usr/libexec/git-core",
                "GIT_TEMPLATE_DIR=" + Init.filesDirPath + "/usr/share/git-core/templates",
                // node 26 默认使用「系统 CA store」，而 termux 编译的 OpenSSL 把
                // CA 路径写死在 termux 的前缀下，我们环境里那条路径不存在，
                // 导致所有 HTTPS 请求报 UNABLE_TO_GET_ISSUER_CERT_LOCALLY。
                // env 里自带 CA bundle，指过去即可。
                "SSL_CERT_FILE=" + Init.filesDirPath + "/usr/etc/tls/cert.pem",
                "PS1=\\[\\e[1\\;31m\\])➜ \\[\\e[1;36m\\]\\W\\[\\e[m\\] ",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                // 注意：libexec.so 用 atoi() 读取该值，末尾不能有多余空格
                "ANDROID10=" + (android10 ? "1" : "0"),
                "TERMUX_ANDROID10=" + (android10 ? "1" : "0"),
                "ANDROID_DATA=/data",
                "ANDROID_ROOT=/system"
        };
        Init.envp = envp;

        Log.i(TAG, describeEnvironment());


        if (PrefManager.isFirstOrReinstall_flag != 0) {
            //First installation
            dialog = DialogUtils.showLoadingDialog(ct, ct.getString(R.string.load_resources));
            ThreadUtil.thread(() -> {
                        try {
                            result = installEnv(ct);
                            // 解压完成后，把 node / git / ssh 换成 nativeLibraryDir 里的
                            // 可执行文件。这一步失败不影响环境本身，只是对应命令不可用。
                            if (result && !installExecutables()) {
                                Log.w(TAG, "部分可执行文件修复失败，node / git 可能不可用");
                            }
                        } catch (IOException | InterruptedException e) {
                            Log.e("InstallEnv  Failed", e.getMessage());
                            result = false;
                        }

                        if (PrefManager.isFirstOrReinstall_flag == 1) initScript(ct);

                        dialog.dismiss();
                        util.runOnUiThread(() -> {
                            if (result)
                                DialogUtils.showConfirmationDialog(ct, "Environment installation successful", ct.getString(R.string.ask_install_hexo),
                                        () -> {
                                            dialog = DialogUtils.showLoadingDialog(ct, "Installing  hexo ");
                                            installHexo(ct);
                                        }, () -> {
                                            dialog.dismiss();
                                            checkPermission(ct);

                                        });
                            else
                                checkPermission(ct);

                            PrefManager.isFirstOrReinstall_flag = 0;


                        });


                    }
            );

        } else {
            // 非首次启动：软链指向不对（悬空/指向旧目录）就修一次
            // （版本升级会走上面的 reinstall 分支，那里已经全部重建）
            boolean needNode = !isNodeLinked();
            boolean needGit = !isLinked(binDir + "/git", gitNativePath);
            if (needNode || needGit) {
                ThreadUtil.thread(() -> {
                    Log.w(TAG, "可执行文件软链需要修复 node=" + needNode + " git=" + needGit);
                    if (needNode) installNodeExecutable();
                    if (needGit) installGitExecutable();
                });
            }
            installGitConfig(); // 幂等：缺失才写，保证 .gitconfig 一直在
        }

    }

    @SuppressLint("SuspiciousIndentation")
    boolean installEnv(Context context) throws IOException, InterruptedException {
        //  String name = "busybox";
        IO.deleteFileOrFolder(filesDirPath + "/usr");
        //new File(filesDirPath).mkdirs();
        IO.copyAssetsDirToSDCard(context, "busybox", binDir);
        IO.copyAssetsDirToSDCard(context, "libexec.so", libDir);
        new File(busyboxPath).setExecutable(true);
        cmdExer.setCwd(Init.binDir);
        // cmdExer.execute(busyboxPath + " --install -s " + binDir, false, true);
        // env / sh / basename 优先指向 nativeLibraryDir 里的 busybox：
        // 这样它们本身就是「可 execve」的文件，脚本 -> env -> node 这条链
        // 不需要再依赖 libexec.so 拦截 execve 才能跑起来。
        String busyboxLinkTarget = new File(busyboxNativePath).isFile() ? busyboxNativePath : "busybox";
        Log.i(TAG, "busybox 软链接目标: " + busyboxLinkTarget);
        cmdExer.execute("ln -sf " + busyboxLinkTarget + " env", false, true);
        cmdExer.execute("ln -sf " + busyboxLinkTarget + " sh", false, true);
        cmdExer.execute("ln -sf " + busyboxLinkTarget + " basename", false, true);
        cmdExer.setCwd(Init.filesDirPath);
        cmdExer.execute("tar -xJf - -C /", false, false);

        OutputStream outputStream = cmdExer.process.getOutputStream();
        FileProviderRegistry.getInstance().addFileProvider(new AssetsFileResolver(context.getAssets()));
        InputStream inputStream = FileProviderRegistry.getInstance().tryGetInputStream("env.tar.xz");
        byte[] buffer = new byte[2048];
        int bytesRead;
        while (true) {

            assert inputStream != null;
            if ((bytesRead = inputStream.read(buffer)) == -1) break;

            outputStream.write(buffer, 0, bytesRead);

        }
        inputStream.close();
        outputStream.close();
        return cmdExer.process.waitFor() == 0;


    }

    /**
     * 把 $PREFIX/bin/node 换成指向 nativeLibraryDir/libnode.so 的软链接。
     * <p>
     * 原因：node 的启动链是 {@code script(#!/usr/bin/env node) -> env -> node}，
     * 中间多了一层 env 进程。Android 10+ 上 files 目录里的文件被 execve 时内核会返回
     * EACCES，原本靠 LD_PRELOAD 的 libexec.so 拦截 execve 来改成
     * {@code linker64 node} 绕开限制，但 env 这一层拦截不到，于是报
     * {@code env: can't execute 'node': Permission denied}。
     * <p>
     * 软链接不会改变 execve 的权限判定（内核会先解析软链接，再看最终 inode 的标签），
     * 所以最终落在 apk_data_file 上，天然可执行，任何调用者都能拉起 node。
     */
    public static boolean installNodeExecutable() {
        File node = new File(nodeDir);
        File nativeNode = new File(nodeNativePath);
        if (!nativeNode.isFile()) {
            Log.w(TAG, "nativeLibraryDir 中没有 libnode.so，保留 files 目录下的 node");
            return false;
        }

        // 1. 首选：软链接到 nativeLibraryDir
        if (linkNode(node, nativeNode) && canRunNode()) {
            Log.i(TAG, "node 已软链接到 " + nativeNode.getAbsolutePath());
            return true;
        }

        // 2. 兜底：复制回 files 目录（仍然依赖 libexec.so + linker64）
        Log.w(TAG, "软链接方式不可用，回退为复制 node 可执行文件");
        if (copyNode(node, nativeNode) && canRunNode()) {
            Log.i(TAG, "node 已复制到 " + node.getAbsolutePath());
            return true;
        }

        Log.e(TAG, "node 仍然不可执行，node / npm / hexo 将无法工作，请查看上面的日志");
        return false;
    }

    /** node + git + ssh 一次性安装 */
    public static boolean installExecutables() {
        boolean nodeOk = installNodeExecutable();
        boolean gitOk = installGitExecutable();
        installGitConfig();
        return nodeOk && gitOk;
    }

    /**
     * 写 $HOME/.gitconfig，把所有目录标记为 git 的 safe.directory。
     * <p>
     * 站点目录建在 /storage/emulated/0（sdcard，FUSE 挂载）时，目录属主不是应用 uid，
     * git 2.35.2+ 会报 "detected dubious ownership" 拒绝工作。HOME 指向应用私有目录，
     * 这里的 --global 配置实际写到 files/.gitconfig，用通配符一次性覆盖所有项目路径。
     */
    public static void installGitConfig() {
        File cfg = new File(filesDirPath, ".gitconfig");
        if (cfg.isFile()) return; // 已存在就不动（幂等，且允许用户自己改）
        try (java.io.FileWriter w = new java.io.FileWriter(cfg)) {
            w.write("[safe]\n" +
                    "\tdirectory = *\n" +
                    "[init]\n" +
                    "\tdefaultBranch = main\n");
            Log.i(TAG, "已写入 " + cfg.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "写入 .gitconfig 失败: " + e.getMessage());
        }
    }

    /**
     * 把 git / ssh 也软链到 nativeLibraryDir。
     * <p>
     * 原因和 node 一样：libexec.so 在 exec ELF 时会把 LD_PRELOAD 从 envp 里剥掉，
     * 所以「node 里再 spawn git」（hexo deploy / npm 生命周期脚本）时钩子已经不在，
     * 只能靠「目标文件本身在 apk_data_file 上」这条路。
     * <p>
     * 关键点是把 {@code usr/libexec/git-core/git} 也一起指过去 —— git-core 里
     * git-add / git-commit 等 141 个虚线命令都是指向它的软链，指过去之后它们
     * 全部解析到 nativeLibraryDir，git 内部 spawn helper 也不需要钩子了。
     */
    public static boolean installGitExecutable() {
        String usr = filesDirPath + "/usr";
        boolean ok = true;
        // git 主程序
        ok &= linkExecutable(usr + "/bin/git", gitNativePath);
        ok &= linkExecutable(usr + "/libexec/git-core/git", gitNativePath);
        // https 协议的 clone / push 需要 git-remote-https（原本是 git-remote-http 的软链）
        ok &= linkExecutable(usr + "/libexec/git-core/git-remote-http", gitRemoteHttpNativePath);
        ok &= linkExecutable(usr + "/libexec/git-core/git-remote-https", gitRemoteHttpNativePath);
        // ssh 协议的 push / pull
        ok &= linkExecutable(usr + "/bin/ssh", sshNativePath);

        int code = cmdExer.execute("git --version", false);
        String out = cmdExer.result == null ? "" : cmdExer.result.trim();
        Log.i(TAG, "git --version => exit=" + code + " output=" + out);
        return ok && code == 0;
    }

    /** 把 linkPath 换成指向 targetPath 的软链接（幂等） */
    static boolean linkExecutable(String linkPath, String targetPath) {
        if (targetPath == null || !new File(targetPath).isFile()) {
            Log.w(TAG, "nativeLibraryDir 缺少 " + targetPath + "，跳过 " + linkPath);
            return false;
        }
        if (isLinked(linkPath, targetPath)) return true;
        try {
            File link = new File(linkPath);
            if (!deleteNode(link)) return false;
            Os.symlink(targetPath, linkPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "创建软链接失败 " + linkPath + " -> " + targetPath + ": " + e.getMessage());
            return false;
        }
    }

    /** linkPath 是否已经是指向 targetPath 的软链接 */
    static boolean isLinked(String linkPath, String targetPath) {
        try {
            return targetPath != null && Os.readlink(linkPath).equals(targetPath);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean linkNode(File node, File nativeNode) {
        try {
            if (!deleteNode(node)) return false;
            Os.symlink(nativeNode.getAbsolutePath(), node.getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "创建 node 软链接失败: " + e.getMessage());
            return false;
        }
    }

    static boolean copyNode(File node, File nativeNode) {
        try {
            if (!deleteNode(node)) return false;
            try (InputStream in = new FileInputStream(nativeNode);
                 OutputStream out = new FileOutputStream(node)) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "复制 node 失败: " + e.getMessage());
            return false;
        }
        try {
            Os.chmod(node.getAbsolutePath(), 0700);
        } catch (Exception e) {
            node.setExecutable(true);
        }
        return true;
    }

    /** 删除 node（可能是普通文件，也可能是软链接；File.delete 两者都能删） */
    static boolean deleteNode(File node) {
        String path = node.getAbsolutePath();
        if (!node.exists() && !isSymlink(node)) return true;
        if (node.delete()) return true;
        // 兜底：用 shell 删除
        cmdExer.execute("rm -f " + path, false);
        return !node.exists() && !isSymlink(node);
    }

    static boolean isSymlink(File file) {
        try {
            return (Os.lstat(file.getAbsolutePath()).st_mode & OsConstants.S_IFMT) == OsConstants.S_IFLNK;
        } catch (Exception e) {
            return false;
        }
    }

    /** node 是否已经指向 nativeLibraryDir/libnode.so */
    public static boolean isNodeLinked() {
        return isLinked(nodeDir, nodeNativePath);
    }

    /** 读取软链接目标，失败返回 null（给日志用） */
    static String readLinkOrNull(String path) {
        try {
            return Os.readlink(path);
        } catch (Exception e) {
            return null;
        }
    }

    /** 执行 node -v 校验 node 是否真的可以被拉起 */
    public static boolean canRunNode() {
        int code = cmdExer.execute("node -v", false);
        String out = cmdExer.result == null ? "" : cmdExer.result.trim();
        Log.i(TAG, "node -v => exit=" + code + " output=" + out);
        // node -v 输出形如 v22.11.0
        return code == 0 && out.matches("(?s).*v\\d+\\.\\d+.*");
    }

    /** 供设置页 / Logcat 排查用的环境快照 */
    public static String describeEnvironment() {
        StringBuilder sb = new StringBuilder();
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" abi=").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?")
                .append('\n');
        sb.append("PREFIX=").append(filesDirPath).append("/usr\n");
        sb.append("nativeLibraryDir=").append(nativeLibDir).append('\n');
        File node = new File(nodeDir == null ? "" : nodeDir);
        File nativeNode = new File(nodeNativePath == null ? "" : nodeNativePath);
        File busybox = new File(busyboxPath == null ? "" : busyboxPath);
        sb.append("node=").append(nodeDir).append(" exists=").append(node.exists())
                .append(" symlink=").append(isSymlink(node))
                .append(" canExec=").append(node.canExecute()).append('\n');
        sb.append("libnode.so=").append(nodeNativePath).append(" exists=").append(nativeNode.exists())
                .append(" canExec=").append(nativeNode.canExecute()).append('\n');
        sb.append("libbusybox.so=").append(busyboxNativePath).append(" exists=").append(new File(busyboxNativePath == null ? "" : busyboxNativePath).exists()).append('\n');
        sb.append("git=").append(binDir).append("/git -> ").append(readLinkOrNull(binDir + "/git"))
                .append(" (期望 ").append(gitNativePath).append(")\n");
        sb.append("git-core/git -> ").append(readLinkOrNull(filesDirPath + "/usr/libexec/git-core/git")).append('\n');
        sb.append("git-remote-https -> ").append(readLinkOrNull(filesDirPath + "/usr/libexec/git-core/git-remote-https")).append('\n');
        sb.append("ssh=").append(readLinkOrNull(binDir + "/ssh")).append('\n');
        sb.append("busybox=").append(busyboxPath).append(" canExec=").append(busybox.canExecute());
        return sb.toString();
    }

    void initScript(Context context) {

        boolean result;
        new File(filesDirPath + "/home").mkdirs();

        for (String type : ProjectManager.getTypeArray()) {
            String scripts = scriptsPath + type;
            result = new File(scripts).mkdirs();
            // if (!result) continue;
            IO.copyAssetsDirToSDCard(context, "scripts/" + type, scripts);
        }


    }


    void installHexo(Activity ct) {
        ThreadUtil.thread(() -> {
            cmdExer.execute("npm config set registry https://npmreg.proxy.ustclug.org", false);
            cmdExer.execute("npm config set ignore-scripts true", false);
            //  cmdExer.execute("",false);
            boolean result = cmdExer.execute("npm install -g hexo-cli", false) == 0;
            dialog.dismiss();
            util.runOnUiThread(() -> {
                Toast.makeText(ct, result ? "Success" : "Failed to install hexo", Toast.LENGTH_SHORT).show();
                checkPermission(ct);
            });
        });

    }


    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static final String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public void checkPermission(Activity ct) {
        // Android 11（API 30）起统一走「所有文件访问权限」，Android 13 上依然有效
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                dismissDialog();
                dialog = DialogUtils.showConfirmationDialog(ct, ct.getString(R.string.sdcard_permisson),
                        ct.getString(R.string.sdcard_description), () -> requestAllFilesAccess(ct), null);
            } else {
                Log.i("Permission", "Android 11+，当前已有所有文件访问权限");
            }
        } else {
            if (ActivityCompat.checkSelfPermission(ct, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                //申请权限
                dismissDialog();
                dialog = DialogUtils.showConfirmationDialog(ct, ct.getString(R.string.sdcard_permisson),
                        ct.getString(R.string.sdcard_description), () -> {
                            ActivityCompat.requestPermissions(ct, PERMISSIONS_STORAGE, REQUEST_EXTERNAL_STORAGE);
                        }, null);
            } else {
                Log.i("Permission", "Android 6.0 以上、11 以下，当前已有权限");
            }
        }
    }

    /** 部分 ROM 没有「应用专属」的所有文件访问页，退回到全局开关页 */
    private void requestAllFilesAccess(Activity ct) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + ct.getPackageName()));
            ct.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                ct.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
                Toast.makeText(ct, "无法打开存储权限设置页，请手动授予「所有文件访问权限」", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void dismissDialog() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }


}
