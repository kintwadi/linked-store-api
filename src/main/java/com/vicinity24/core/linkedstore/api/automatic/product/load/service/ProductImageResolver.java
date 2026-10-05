package com.vicinity24.core.linkedstore.api.automatic.product.load.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves image references from uploaded templates into URLs the catalog
 * actually serves. Three lookup strategies are tried in order:
 *
 * <ol>
 *   <li><b>Filename match</b> inside {@code frontend/products/**} (the folder
 *       you included with sample images). Matches both bare names
 *       ("cover.png", "1791106383.png") and product-scoped paths
 *       ("p1/cover.png").</li>
 *   <li><b>External URL</b> (http(s)://...) — downloaded on demand and cached
 *       in the static bucket so subsequent renders are local.</li>
 *   <li><b>Absolute / relative local path</b> — copied if readable and within
 *       the repo root; otherwise left unchanged so existing Unsplash URLs in
 *       the seed data continue to work 100% unmodified.</li>
 * </ol>
 *
 * <p>The goal: no existing features are broken (ProductCatalogSeeder URLs,
 * existing variant primary images, home page cards, QR share cards).</p>
 */
@Slf4j
@Service
public class ProductImageResolver {

    private static final Set<String> ALLOWED_EXT =
            Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".svg");

    private static final List<String> FALLBACK_SOURCE_ROOTS = List.of(
            // User-placed product folder on Windows desktop
            "C:/Users/core101/Desktop/products",
            // Legacy in-repo location
            "${user.dir}/../frontend/products"
    );

    private final List<Path> sourceRoots;
    private final Path staticOutputRoot;
    private final String publicBaseUrl;
    private final String contextPath;

    public ProductImageResolver(
            @Value("${linkedstore.products.upload.frontend-source:}") String frontendSource,
            @Value("${linkedstore.products.upload.static-target:${user.dir}/src/main/resources/static/products}") String staticTarget,
            @Value("${linkedstore.products.upload.public-base-url:}") String publicBaseUrl,
            @Value("${server.servlet.context-path:}") String contextPath
    ) {
        List<Path> roots = new ArrayList<>();
        if (StringUtils.hasText(frontendSource)) {
            Path configured = resolveAbsolute(frontendSource).normalize();
            if (Files.isDirectory(configured)) roots.add(configured);
            else roots.add(configured); // still register even if missing, so logs surface later lookup failures
        }
        for (String fallback : FALLBACK_SOURCE_ROOTS) {
            Path resolved = resolveAbsolute(fallback).normalize();
            // Only add if not already present by absolute path
            boolean already = roots.stream().anyMatch(p -> p.toAbsolutePath().normalize().equals(resolved));
            if (!already) roots.add(resolved);
        }
        this.sourceRoots = Collections.unmodifiableList(roots);
        this.staticOutputRoot = resolveAbsolute(staticTarget).normalize();
        this.publicBaseUrl = Optional.ofNullable(publicBaseUrl).map(String::trim).orElse("");
        this.contextPath = Optional.ofNullable(contextPath).map(String::trim).orElse("");
    }

    /** Primary configured / fallback source roots (ordered search order). Exposed for diagnostics. */
    public List<Path> getSourceRoots() {
        return sourceRoots;
    }

    /** Public-facing accessor so templates can discover available gallery images. */
    public Path getFrontendProductsRoot() {
        return sourceRoots.stream().filter(Files::isDirectory).findFirst().orElse(sourceRoots.get(0));
    }

    /**
     * For the provided image reference (bare filename / relative path / URL /
     * absolute path), copy the asset to the served static folder and return
     * the URL that the frontend should use.
     *
     * <p>Returns {@link CopyResult} with a boolean flag so callers can tally
     * a "files copied" vs "files skipped" counter for the upload report.</p>
     */
    public CopyResult resolveAndCopy(String imageRef, String namespace) throws IOException {
        String ref = Optional.ofNullable(imageRef).map(String::trim).orElse(null);
        if (ref == null || ref.isEmpty()) {
            return new CopyResult(null, false);
        }

        if (ref.startsWith("http://") || ref.startsWith("https://") || ref.startsWith("//")) {
            try {
                String url = ref.startsWith("//") ? "https:" + ref : ref;
                return downloadExternal(url, namespace);
            } catch (Exception ex) {
                log.warn("ProductImageResolver: failed to download external image {} — leaving as-is", ref, ex);
                return new CopyResult(ref, false);
            }
        }

        Path userPath = Paths.get(ref);
        Optional<Path> inFrontend = resolveFromFrontendLibrary(userPath);
        if (inFrontend.isPresent()) {
            return copyToStatic(inFrontend.get(), namespace);
        }

        Path absolute = resolveAbsolute(ref);
        if (Files.exists(absolute)) {
            return copyToStatic(absolute, namespace);
        }

        return new CopyResult(ref, false);
    }

    /**
     * Looks for a file inside any configured source root. Supports both
     * {@code "p1/cover.png"} paths and a bare {@code "cover.png"} search
     * (picks the first match).
     */
    public Optional<Path> resolveFromFrontendLibrary(Path inputPath) {
        String fileName = inputPath.getFileName() == null ? null : inputPath.getFileName().toString();
        if (fileName == null) return Optional.empty();

        for (Path root : sourceRoots) {
            if (!Files.isDirectory(root)) continue;

            // 1) sourceRoot + full input (p1/cover.png)
            Path full = root.resolve(inputPath).normalize();
            if (Files.isRegularFile(full)) return Optional.of(full);

            // 2) depth search by bare filename
            if (hasImageExt(fileName)) {
                try (Stream<Path> s = Files.walk(root, 4)) {
                    Optional<Path> match = s.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName() != null && p.getFileName().toString().equalsIgnoreCase(fileName))
                            .findFirst();
                    if (match.isPresent()) return match;
                } catch (IOException e) {
                    log.warn("ProductImageResolver: walking root {} failed", root, e);
                }
            }
        }
        return Optional.empty();
    }

    private CopyResult copyToStatic(Path source, String namespace) throws IOException {
        String filename = source.getFileName() == null ? "image.bin" : source.getFileName().toString();
        String digest = sha256(source);
        String ext = extractExt(filename).orElse(".bin");
        String targetName = safeNamespace(namespace) + "_" + digest + ext;
        Path outDir = staticOutputRoot.resolve("uploaded");
        Files.createDirectories(outDir);
        Path target = outDir.resolve(targetName).normalize();
        boolean copied = false;
        if (!Files.exists(target)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            copied = true;
        }
        return new CopyResult(publicPath("uploaded/" + target.getFileName()), copied);
    }

    private CopyResult downloadExternal(String url, String namespace) throws IOException {
        URI uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String last = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : ("remote-" + Math.abs(url.hashCode()));
        String ext = extractExt(last).orElse(".png");

        String digest;
        try (InputStream is = new URL(url).openStream()) {
            byte[] bytes = is.readAllBytes();
            digest = sha256(bytes);
            String targetName = safeNamespace(namespace) + "_rmt_" + digest + ext;
            Path outDir = staticOutputRoot.resolve("uploaded");
            Files.createDirectories(outDir);
            Path target = outDir.resolve(targetName).normalize();
            boolean copied = !Files.exists(target);
            if (copied) Files.write(target, bytes);
            return new CopyResult(publicPath("uploaded/" + target.getFileName()), copied);
        }
    }

    private String publicPath(String relative) {
        String base = publicBaseUrl == null ? "" : publicBaseUrl.trim();
        String ctx = contextPath == null ? "" : contextPath.trim();
        String ctxNorm = ctx.isEmpty() ? "" : (ctx.startsWith("/") ? ctx : "/" + ctx);
        ctxNorm = ctxNorm.endsWith("/") ? ctxNorm.substring(0, ctxNorm.length() - 1) : ctxNorm;
        String suffix = relative == null ? "" : (relative.startsWith("/") ? relative.substring(1) : relative);
        String path = "/products" + ctxNorm + "/" + suffix;
        if (base.isEmpty()) return path;
        String baseNorm = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return baseNorm + path;
    }

    private static Optional<String> extractExt(String name) {
        int idx = name.lastIndexOf('.');
        if (idx < 0) return Optional.empty();
        String ext = name.substring(idx).toLowerCase();
        if (ext.length() > 6) return Optional.empty();
        return Optional.of(ext);
    }

    private static boolean hasImageExt(String name) {
        return extractExt(name).map(ALLOWED_EXT::contains).orElse(false);
    }

    private static String safeNamespace(String ns) {
        String s = Optional.ofNullable(ns).map(String::trim).orElse("gen");
        if (s.isEmpty()) s = "gen";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') sb.append(c);
            else sb.append('_');
        }
        String out = sb.toString();
        return out.length() > 40 ? out.substring(0, 40) : out;
    }

    private static Path resolveAbsolute(String spec) {
        if (!StringUtils.hasText(spec)) return Paths.get("").toAbsolutePath();
        Path p = Paths.get(spec);
        if (p.isAbsolute()) return p.normalize();
        return Paths.get(System.getProperty("user.dir", ".")).resolve(p).toAbsolutePath().normalize();
    }

    private static String sha256(Path file) throws IOException {
        return sha256(Files.readAllBytes(file));
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes), 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record CopyResult(String url, boolean copied) {
        public List<String> asList() { return url == null ? List.of() : List.of(url); }
    }
}
