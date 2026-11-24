package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A folder of paper PDFs in which file names are labels and papers are told apart by identity.
 *
 * <p>The shared library and every topic's {@code papers/} are both one of these, so the rule
 * exists once. Two guarantees:</p>
 * <ul>
 *   <li>{@link #find} never answers with a file just because its name matches;</li>
 *   <li>{@link #add} never overwrites a paper — another paper that wants the same name gets
 *       that name plus its identifier — and never adds a paper that is already there.</li>
 * </ul>
 */
final class PaperShelf {

    /** What is known about one file. */
    record Known(PdfIdentity printed, PaperIdentity recorded) {
    }

    /** Describes a file on the shelf; empty when it is not a readable PDF. */
    @FunctionalInterface
    interface Catalog {
        Optional<Known> describe(Path pdf);
    }

    private static final int MAX_NAME = 80;

    /** Choosing a free name and taking it must not interleave with another download. */
    private static final Object PUBLISH = new Object();

    private final Path dir;
    private final Catalog catalog;

    PaperShelf(Path dir, Catalog catalog) {
        this.dir = dir;
        this.catalog = catalog;
    }

    /**
     * The file that is the wanted paper, whatever it is called.
     *
     * @param name the label asked for, or null to go by identity alone
     */
    Optional<Path> find(String name, PaperIdentity wanted) {
        Path byTitle = null;
        Path byName = null;
        for (Path pdf : pdfs(name)) {
            boolean named = name != null && baseName(pdf).equalsIgnoreCase(name);
            if (wanted.isEmpty() && !named) {
                continue;
            }
            Known known = catalog.describe(pdf).orElse(null);
            if (known == null) {
                continue;
            }
            PaperMatch match = PaperMatch.of(wanted, known.recorded(), known.printed(), baseName(pdf));
            if (match == PaperMatch.SAME_ID) {
                return Optional.of(pdf);
            }
            if (match == PaperMatch.SAME_TITLE && byTitle == null) {
                byTitle = pdf;
            }
            if (named && byName == null && match.acceptsName(wanted, known.recorded(), known.printed())) {
                byName = pdf;
            }
        }
        return Optional.ofNullable(byTitle != null ? byTitle : byName);
    }

    /**
     * Puts a copy of a PDF on the shelf unless the same paper is already there.
     *
     * @param name     the preferred label, already a safe file name
     * @param identity what is certain about this file, from its source. It can end up in the
     *                 file's name, and names by identifier are believed later — so a mere claim
     *                 of the request must not be passed here
     * @return where the paper is: the new copy, or the file that already held it
     * @throws IllegalArgumentException when the source is not a readable PDF
     */
    Path add(Path source, String name, PaperIdentity identity) throws IOException {
        PdfIdentity printed = PdfIdentityReader.identityOf(source)
                .orElseThrow(() -> new IllegalArgumentException("不是可解析的 PDF：" + source));
        PaperIdentity confirmed = identity.confirmedBy(printed);
        String hash = PaperStore.sha256(source);
        if (hash == null) {
            throw new IOException("无法读取文件：" + source);
        }
        synchronized (PUBLISH) {
            Files.createDirectories(dir);
            for (Path pdf : pdfs(name)) {
                if (Files.size(pdf) == Files.size(source) && hash.equals(PaperStore.sha256(pdf))) {
                    return pdf;
                }
            }
            Optional<Path> held = find(null, confirmed);
            if (held.isPresent()) {
                return held.get();
            }
            // Temp + move keeps an interrupted copy from leaving half a PDF under a final name.
            Path temp = Files.createTempFile(dir, ".paper-", ".part");
            try {
                Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
                for (String label : labels(name, confirmed, hash)) {
                    Path target = dir.resolve(label + ".pdf").normalize();
                    if (!target.startsWith(dir)) {
                        throw new IllegalArgumentException("resolved path escapes the papers directory: " + target);
                    }
                    if (!Files.exists(target)) {
                        Files.move(temp, target);
                        return target;
                    }
                    if (!PaperStore.hasPdfMagic(target)) {
                        // An error page or an empty leftover. Anything that might be a paper is
                        // kept: an encrypted PDF cannot be read either.
                        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        return target;
                    }
                }
                throw new IOException("没有可用的文件名：" + name);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** The name a paper gets when its plain label belongs to another paper. */
    static String distinctName(String name, PaperIdentity identity) {
        String token = identity.fileToken();
        return token == null ? name : withToken(name, token);
    }

    private static List<String> labels(String name, PaperIdentity identity, String hash) {
        return List.of(name, distinctName(name, identity), withToken(name, hash.substring(0, 8)));
    }

    private static String withToken(String name, String token) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals(token) || lower.endsWith("-" + token)) {
            return name;
        }
        int room = MAX_NAME - token.length() - 1;
        String base = name.length() > room ? name.substring(0, room) : name;
        return WorkspacePaths.slugify(base + "-" + token, "论文短名");
    }

    /** Every PDF here, the one carrying the given name first. */
    private List<Path> pdfs(String name) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return new ArrayList<>(stream
                    .filter(Files::isRegularFile)
                    .filter(p -> WorkspacePaths.hasExtension(p.getFileName().toString(), ".pdf"))
                    .sorted(Comparator.comparing((Path p) -> name == null || !baseName(p).equalsIgnoreCase(name))
                            .thenComparing(p -> p.getFileName().toString()))
                    .toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String baseName(Path pdf) {
        return PaperStore.shortNameOf(pdf);
    }
}
