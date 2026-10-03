package com.visitorbridge.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.visitorbridge.config.VmsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrCodeService {

    private static final int QR_SIZE_PX = 300;
    private static final String LEGACY_DIR_PREFIX = "qr-codes";
    private static final int MAX_NAME_PART = 40;
    private static final int MAX_REG_ID_PART = 60;
    private static final String FALLBACK_SLUG = "visitor";

    private final VmsProperties vmsProperties;

    /**
     * Builds a filename that is recognisable to a human testing the flow while staying
     * collision-free and safe to write to disk.
     *
     * <p>Visitor names are not unique, so the registrationId is always appended. Without it
     * two visitors sharing a name would overwrite each other's QR, and because
     * {@code card_number_out} falls back to {@code card_number_in} that would hand one guest
     * the other guest's credential.
     *
     * <p>Both parts are sanitised: the registrationId is client-supplied, so a value like
     * {@code ../../etc} must not escape the storage directory.
     */
    public String buildFileBaseName(String visitorName, String registrationId, String direction) {
        String namePart = slugify(visitorName, MAX_NAME_PART);
        String regPart = slugify(registrationId, MAX_REG_ID_PART);
        if (namePart.isBlank()) {
            namePart = FALLBACK_SLUG;
        }
        if (regPart.isBlank()) {
            regPart = FALLBACK_SLUG;
        }
        String suffix = direction == null ? "" : "_" + sanitize(direction).toLowerCase();
        return namePart + "-" + regPart + suffix;
    }

    public String generateAndSave(String credentialNumber, String fileBaseName) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 2
            );
            BitMatrix matrix = new QRCodeWriter()
                    .encode(credentialNumber, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX, hints);

            Path dir = Paths.get(storagePath()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(fileBaseName + ".png").normalize();
            if (!file.startsWith(dir)) {
                log.warn("Refusing to write QR code outside storage directory: {}", fileBaseName);
                return null;
            }
            MatrixToImageWriter.writeToPath(matrix, "PNG", file);

            log.info("QR code generated for {} -> {}", TransactionLogger.maskCardNumber(credentialNumber), file);
            return fileBaseName + ".png";
        } catch (Exception e) {
            log.warn("QR code generation failed for credential {}: {}",
                    TransactionLogger.maskCardNumber(credentialNumber), e.getMessage());
            return null;
        }
    }

    /**
     * Replaces every character that is not a lowercase letter or digit with a hyphen and
     * collapses runs. Non-ASCII names (e.g. Chinese) reduce to an empty slug, so callers must
     * apply a fallback.
     */
    private String slugify(String raw, int maxLength) {
        String cleaned = sanitize(raw).replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength).replaceAll("-$", "");
        }
        return cleaned;
    }

    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toLowerCase().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    public byte[] readQrCode(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        try {
            Path file = resolve(relativePath);
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            log.warn("Failed to read QR code {}: {}", relativePath, e.getMessage());
            return null;
        }
    }

    public void deleteQrCode(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(relativePath));
            log.info("QR code deleted: {}", relativePath);
        } catch (IOException e) {
            log.warn("Failed to delete QR code {}: {}", relativePath, e.getMessage());
        }
    }

    public String buildServeUrl(String registrationId, String type) {
        return vmsProperties.getQr().getBaseServeUrl()
                + "/api/v1/bookings/" + registrationId + "/qr/" + type;
    }

    private Path resolve(String storedPath) {
        // Rows written before the filename change store "qr-codes/<name>.png"; newer rows store
        // just "<name>.png". Strip the legacy prefix so old bookings stay readable.
        String name = storedPath.startsWith(LEGACY_DIR_PREFIX + "/")
                ? storedPath.substring(LEGACY_DIR_PREFIX.length() + 1)
                : storedPath;
        return Paths.get(storagePath()).toAbsolutePath().normalize().resolve(name).normalize();
    }

    private String storagePath() {
        return vmsProperties.getQr().getStoragePath();
    }
}
