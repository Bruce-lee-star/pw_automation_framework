package com.hsbc.cmb.hk.dbb.automation.framework.web.config;

import com.hsbc.cmb.hk.dbb.automation.framework.common.config.ConfigResolver;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigResolveOptions;
import com.typesafe.config.ConfigValue;
import com.typesafe.config.ConfigValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 框架自有的文件层 {@link ConfigResolver} SPI 实现（DEV-V5 / M1a）。
 *
 * <p>合并 {@code pw.properties}（扁平 Properties）与 {@code pw.conf}（HOCON），
 * 递归扁平化后交给 {@code ConfigReader}；解密不在本层（由 {@code ConfigReader} 统一处理
 * {@code ENC(...)}）。更高的系统属性 / 环境变量<b>单键</b>覆盖仍由 {@code ConfigReader}
 * 负责，与本 resolver 无关。</p>
 *
 * <p><b>读取来源与优先级</b>（低 → 高，后加载者覆盖先加载者）：</p>
 * <ol>
 *   <li>classpath 根下的 {@code pw.properties} → {@code pw.conf}（随构建物发布的默认值）；</li>
 *   <li><b>工程根目录</b>下的 {@code pw.properties} → {@code pw.conf}。工程根目录取
 *       {@code System.getProperty("user.dir")}，即 Maven / IDEA 运行时的 {@code ${basedir}}
 *       （与 {@code pom.xml} 同级）。仅从 classloader 读取时，放在这里的配置会静默失效；</li>
 *   <li>显式目录 {@code -Dpw.config.dir=...} / 环境变量 {@code PW_CONFIG_DIR}
 *       （其下同样按 {@code pw.properties} → {@code pw.conf} 顺序），
 *       以及显式单文件 {@code -Dpw.config.file=...} / {@code PW_CONFIG_FILE}。</li>
 * </ol>
 *
 * <p><b>为什么外部文件优先于 classpath</b>：classpath 里的是构建物内置默认值，工程根目录 /
 * 显式路径是部署与 CI 注入的覆盖层（CI 全新检出时配置常落在工程目录或工程外路径）。
 * 与 Spring Boot「外部 config 覆盖 classpath 内置配置」约定一致；两者内容相同时结果不变。</p>
 *
 * <p><b>为什么显式位置用专用系统属性而不是配置键</b>：文件来源必须先于配置读取确定，
 * 不能用配置系统自身引导配置系统（鸡生蛋）。这些键只由本类直读，
 * 不进入 {@code ConfigRegistry}，也不参与 {@code ConfigKeysGoldenTest} 键集。</p>
 *
 * <p><b>HOCON 语义</b>：同一 Typesafe Config 解析器，嵌套对象与点分叶子键
 * （如 {@code token.security.url}）统一递归扁平化为点分键。缺文件 / 解析失败均跳过该来源，
 * 返回 {@code null} 由上层降级，绝不影响其它来源与内置层。</p>
 *
 * <p><b>可观测性</b>：启动时以 INFO 打印实际命中的来源路径与键数（只打印路径，不打印值，
 * 避免凭据泄露）；未命中任何来源时降级为 DEBUG——这是排查「配置静默失效」的第一手证据。</p>
 *
 * <p><b>线程与生命周期</b>：{@link #snapshot()} 双重检查懒加载一次性快照，与
 * {@code ConfigReader} 的启动期快照语义一致；加载路径只读 JVM 启动期状态，无共享可变状态。</p>
 */
public final class FileConfigResolver implements ConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(FileConfigResolver.class);

    /** 显式配置目录（系统属性），其下查找 pw.properties / pw.conf。 */
    public static final String CONFIG_DIR_PROPERTY = "pw.config.dir";
    /** 显式配置文件（系统属性），单文件，优先级最高。 */
    public static final String CONFIG_FILE_PROPERTY = "pw.config.file";
    /** 显式配置目录的环境变量形式。 */
    public static final String CONFIG_DIR_ENV = "PW_CONFIG_DIR";
    /** 显式配置文件的环境变量形式。 */
    public static final String CONFIG_FILE_ENV = "PW_CONFIG_FILE";

    /** 读取来源（文件名），<b>从低到高</b>排列，后读覆盖先读。 */
    private static final String[] SOURCES = {
            "pw.properties", // 扁平 key=value
            "pw.conf"        // HOCON
    };

    /** 懒加载 + 缓存的一次性扁平化结果；线程安全（双重检查）。 */
    private volatile Map<String, String> flattened;

    @Override
    public String resolve(String key) {
        if (key == null) {
            return null;
        }
        return snapshot().get(key);
    }

    private Map<String, String> snapshot() {
        Map<String, String> local = flattened;
        if (local == null) {
            synchronized (this) {
                local = flattened;
                if (local == null) {
                    local = load(externalDirectories(), explicitFiles());
                    flattened = local;
                }
            }
        }
        return local;
    }

    /**
     * 按「classpath → 工程根目录 → 显式目录 → 显式文件」逐层合并。
     * 每个来源按 {@link #SOURCES} 顺序读取（后覆盖先），层级之间外部覆盖 classpath。
     *
     * <p>包级私有：供单测注入临时目录，无需改写 JVM 全局 {@code user.dir}。</p>
     */
    static Map<String, String> load(List<Path> directories, List<Path> files) {
        Map<String, String> merged = new LinkedHashMap<>();
        List<String> loaded = new ArrayList<>();

        for (String source : SOURCES) {
            String display = "classpath:" + source;
            merge(merged, loaded, parse(source, readClasspath(source), display), display);
        }
        if (directories != null) {
            for (Path directory : directories) {
                if (directory == null) {
                    continue;
                }
                for (String source : SOURCES) {
                    Path file = directory.resolve(source);
                    String display = file.toString();
                    merge(merged, loaded, parse(source, readDisk(file), display), display);
                }
            }
        }
        if (files != null) {
            for (Path file : files) {
                if (file == null) {
                    continue;
                }
                String display = file.toString();
                merge(merged, loaded,
                        parse(file.getFileName().toString(), readDisk(file), display), display);
            }
        }

        if (loaded.isEmpty()) {
            log.debug("[FileConfigResolver] no pw.properties / pw.conf found"
                    + " (classpath, project root or explicit location)");
        } else {
            log.info("[FileConfigResolver] loaded {} file config key(s) from {}",
                    merged.size(), loaded);
        }
        return merged;
    }

    private static void merge(Map<String, String> merged, List<String> loaded,
                              Map<String, String> parsed, String display) {
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        merged.putAll(parsed);
        loaded.add(display + " (" + parsed.size() + " keys)");
    }

    /** 按扩展名分派：{@code .properties} 走 Java Properties，其余（pw.conf）走 HOCON。 */
    private static Map<String, String> parse(String name, byte[] content, String display) {
        if (content == null) {
            return Map.of();
        }
        return name.endsWith(".properties")
                ? parseProperties(content, display)
                : parseHocon(content, display);
    }

    /** 读取扁平 properties（Java Properties 语义）；缺文件 / IO 错 → 空 Map。 */
    private static Map<String, String> parseProperties(byte[] content, String display) {
        Properties props = new Properties();
        try (InputStream in = new ByteArrayInputStream(content)) {
            props.load(in);
        } catch (IOException | RuntimeException e) {
            log.warn("[FileConfigResolver] failed to read properties '{}', skipped: {}",
                    display, e.toString());
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : props.stringPropertyNames()) {
            out.put(name, props.getProperty(name));
        }
        return out;
    }

    /** 读取并扁平化 HOCON；缺文件 / 语法错 → 空 Map。 */
    private static Map<String, String> parseHocon(byte[] content, String display) {
        try {
            Config parsed = ConfigFactory.parseString(new String(content, StandardCharsets.UTF_8));
            // 容忍未定义 substitution（conf 可能引用缺省项），其余原样保留
            Config resolved = parsed.resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true));
            Map<String, String> out = new LinkedHashMap<>();
            flatten(resolved, "", out);
            return out;
        } catch (RuntimeException e) {
            log.warn("[FileConfigResolver] failed to parse HOCON '{}', skipped: {}",
                    display, e.toString());
            return Map.of();
        }
    }

    /**
     * 递归扁平化：对象下钻，叶子（含点分键如 {@code token.security.url}）记为 {@code 点分键=字符串值}。
     * package-private 供单测直接验证（不依赖 classpath 夹具）。
     */
    static void flatten(Config config, String prefix, Map<String, String> out) {
        if (config == null) {
            return;
        }
        for (Map.Entry<String, ConfigValue> e : config.root().entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            ConfigValue value = e.getValue();
            if (value.valueType() == ConfigValueType.OBJECT) {
                flatten(((ConfigObject) value).toConfig(), key, out);
            } else {
                out.put(key, render(value));
            }
        }
    }

    private static String render(ConfigValue value) {
        Object unwrapped = value.unwrapped();
        return unwrapped == null ? null : unwrapped.toString();
    }

    /** classpath 来源：TCCL 优先，回落本类定义类加载器（保持既有语义）。 */
    private static byte[] readClasspath(String resource) {
        try (InputStream in = openResource(resource)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            log.warn("[FileConfigResolver] failed to read '{}', skipped: {}",
                    resource, e.toString());
            return null;
        }
    }

    private static InputStream openResource(String resource) {
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        InputStream in = tccl == null ? null : tccl.getResourceAsStream(resource);
        return in != null ? in : FileConfigResolver.class.getClassLoader().getResourceAsStream(resource);
    }

    /** 磁盘来源：不存在 / 不可读静默跳过（工程根目录没有配置文件是正常情况）。 */
    private static byte[] readDisk(Path file) {
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            return null;
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            log.warn("[FileConfigResolver] failed to read '{}', skipped: {}",
                    file, e.toString());
            return null;
        }
    }

    /** 工程根目录在前（低），显式目录在后（高）；显式位置缺失时告警，避免静默失效重演。 */
    private static List<Path> externalDirectories() {
        List<Path> directories = new ArrayList<>();
        Path projectRoot = projectRoot();
        if (projectRoot != null) {
            directories.add(projectRoot);
        }
        String configured = firstNonBlank(System.getProperty(CONFIG_DIR_PROPERTY),
                System.getenv(CONFIG_DIR_ENV));
        if (configured != null) {
            Path directory = resolvePath(configured);
            if (directory == null || !Files.isDirectory(directory)) {
                log.warn("[FileConfigResolver] {} points to a missing/invalid directory: {}",
                        CONFIG_DIR_PROPERTY, configured);
            } else {
                directories.add(directory);
            }
        }
        return directories;
    }

    private static List<Path> explicitFiles() {
        List<Path> files = new ArrayList<>();
        String configured = firstNonBlank(System.getProperty(CONFIG_FILE_PROPERTY),
                System.getenv(CONFIG_FILE_ENV));
        if (configured != null) {
            Path file = resolvePath(configured);
            if (file == null || !Files.isRegularFile(file)) {
                log.warn("[FileConfigResolver] {} points to a missing/invalid file: {}",
                        CONFIG_FILE_PROPERTY, configured);
            } else {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * 工程根目录 = JVM 工作目录（Maven surefire/failsafe 默认 ${basedir}）。
     * <p>刻意不复用 {@link #resolvePath(String)}：后者对相对路径会回调本方法，
     * 若 {@code user.dir} 恰为相对路径将形成无限递归。</p>
     */
    static Path projectRoot() {
        String workingDirectory = System.getProperty("user.dir");
        if (workingDirectory == null || workingDirectory.isBlank()) {
            return null;
        }
        try {
            return Paths.get(workingDirectory.trim()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("[FileConfigResolver] invalid working directory '{}': {}",
                    workingDirectory, e.toString());
            return null;
        }
    }

    /** 相对路径按工程根目录解析；非法路径返回 null 而非抛出，保证配置层永不打断启动。 */
    private static Path resolvePath(String raw) {
        try {
            Path path = Paths.get(raw.trim());
            if (path.isAbsolute()) {
                return path.normalize();
            }
            Path root = projectRoot();
            return (root == null ? path : root.resolve(path)).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            log.warn("[FileConfigResolver] invalid config path '{}': {}", raw, e.toString());
            return null;
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
