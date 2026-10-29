package com.healthclaim.casedesk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Stores uploaded identity files outside the web root under random names.
 * The file TYPE is decided by sniffing the first bytes, never by the client-supplied name or content-type.
 */
@Service
public class FileStorageService {

    private final Path root;

    public FileStorageService(@Value("${app.upload-dir:./uploads}") String dir) throws IOException {
        this.root = Paths.get(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String store(MultipartFile f, boolean allowPdf, long maxBytes) {
        if (f == null || f.isEmpty()) throw bad("An uploaded file is empty.");
        if (f.getSize() > maxBytes) throw bad("File is too large (max " + (maxBytes / 1024 / 1024) + " MB).");
        try {
            byte[] head;
            try (InputStream in = f.getInputStream()) { head = in.readNBytes(8); }
            String ext = sniff(head, allowPdf);
            if (ext == null) throw bad(allowPdf ? "Only JPG, PNG or PDF files are allowed." : "Only JPG or PNG images are allowed.");
            String name = UUID.randomUUID() + "." + ext;
            try (InputStream in = f.getInputStream()) {
                Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            return name;
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not store file.");
        }
    }

    public byte[] read(String name) {
        try {
            Path p = root.resolve(name).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
    }

    public void delete(String name) {
        if (name == null) return;
        try {
            Path p = root.resolve(name).normalize();
            if (p.startsWith(root)) Files.deleteIfExists(p);
        } catch (IOException ignored) { }
    }

    public String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".pdf")) return "application/pdf";
        return "image/jpeg";
    }

    private static String sniff(byte[] b, boolean allowPdf) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
        if (b.length >= 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
        if (allowPdf && b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "pdf";
        return null;
    }

    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
}
