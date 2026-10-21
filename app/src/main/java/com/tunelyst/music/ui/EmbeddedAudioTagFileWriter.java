package com.tunelyst.music.ui;

import android.os.ParcelFileDescriptor;

import com.kyant.taglib.Metadata;
import com.kyant.taglib.Picture;
import com.kyant.taglib.TagLib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Thin Java bridge to the Kotlin TagLib AAR; using Java avoids depending on its newer Kotlin metadata. */
final class EmbeddedAudioTagFileWriter {
    static final class Result {
        final boolean supportedFormat;
        final boolean metadataSaved;
        final boolean metadataVerified;
        final List<String> unsupportedMetadataKeys;
        final boolean artworkAttempted;
        final boolean artworkSaved;
        final boolean fileMayHaveChanged;

        Result(
                boolean supportedFormat,
                boolean metadataSaved,
                boolean metadataVerified,
                List<String> unsupportedMetadataKeys,
                boolean artworkAttempted,
                boolean artworkSaved,
                boolean fileMayHaveChanged
        ) {
            this.supportedFormat = supportedFormat;
            this.metadataSaved = metadataSaved;
            this.metadataVerified = metadataVerified;
            this.unsupportedMetadataKeys = unsupportedMetadataKeys;
            this.artworkAttempted = artworkAttempted;
            this.artworkSaved = artworkSaved;
            this.fileMayHaveChanged = fileMayHaveChanged;
        }
    }

    private EmbeddedAudioTagFileWriter() {}

    static Result write(
            ParcelFileDescriptor descriptor,
            Map<String, String> updates,
            byte[] artworkBytes,
            String artworkMimeType
    ) throws IOException {
        Metadata current = TagLib.getMetadata(duplicateFd(descriptor), false);
        if (current == null) {
            return new Result(false, false, false, Collections.emptyList(), false, false, false);
        }

        boolean metadataSaved = true;
        boolean metadataVerified = updates.isEmpty();
        List<String> unsupportedKeys = new ArrayList<>();
        if (!updates.isEmpty()) {
            HashMap<String, String[]> currentProperties = current.getPropertyMap();
            HashMap<String, String[]> updatedProperties = new HashMap<>(currentProperties);
            for (Map.Entry<String, String> entry : updates.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (value == null || value.trim().isEmpty()) {
                    updatedProperties.remove(key);
                    continue;
                }
                value = preserveNumberTotal(key, value, currentProperties);
                value = preserveDatePrecision(key, value, currentProperties);
                updatedProperties.put(key, new String[]{value});
            }

            try {
                metadataSaved = TagLib.savePropertyMap(duplicateFd(descriptor), updatedProperties);
            } catch (RuntimeException | LinkageError failure) {
                rethrowSecurityException(failure);
                return new Result(true, false, false, new ArrayList<>(updates.keySet()), false, false, true);
            }
            if (!metadataSaved) {
                // Native writers may fail after touching the file; report uncertainty and refresh the catalog.
                return new Result(true, false, false, new ArrayList<>(updates.keySet()), false, false, true);
            }

            try {
                Metadata writtenMetadata = TagLib.getMetadata(duplicateFd(descriptor), false);
                metadataVerified = writtenMetadata != null;
                if (writtenMetadata == null) {
                    unsupportedKeys.addAll(updates.keySet());
                } else {
                    HashMap<String, String[]> writtenProperties = writtenMetadata.getPropertyMap();
                    for (Map.Entry<String, String> entry : updates.entrySet()) {
                        String key = entry.getKey();
                        String expected = entry.getValue();
                        if (!matches(key, expected, writtenProperties.get(key))) {
                            unsupportedKeys.add(key);
                        }
                    }
                }
            } catch (IOException | RuntimeException | LinkageError failure) {
                rethrowSecurityException(failure);
                unsupportedKeys.addAll(updates.keySet());
            }
        }

        boolean artworkAttempted = artworkBytes != null;
        boolean artworkSaved = artworkBytes == null || saveArtwork(
                descriptor,
                artworkBytes,
                artworkMimeType
        );
        boolean fileMayHaveChanged = !updates.isEmpty() || artworkAttempted;
        return new Result(true, metadataSaved, metadataVerified, unsupportedKeys,
                artworkAttempted, artworkSaved, fileMayHaveChanged);
    }

    private static boolean saveArtwork(
            ParcelFileDescriptor descriptor,
            byte[] artworkBytes,
            String artworkMimeType
    ) throws IOException {
        try {
            Picture[] existingPictures = TagLib.getPictures(duplicateFd(descriptor));
            if (existingPictures == null) existingPictures = new Picture[0];
            ArrayList<Picture> replacementPictures = new ArrayList<>();
            for (Picture picture : existingPictures) {
                if (picture != null && !"Front Cover".equalsIgnoreCase(picture.getPictureType())) {
                    replacementPictures.add(picture);
                }
            }
            replacementPictures.add(0, new Picture(
                    artworkBytes,
                    "Tunelyst replacement cover",
                    "Front Cover",
                    artworkMimeType == null ? "image/jpeg" : artworkMimeType
            ));
            boolean pictureWriteSucceeded = TagLib.savePictures(
                    duplicateFd(descriptor),
                    replacementPictures.toArray(new Picture[0])
            );
            if (!pictureWriteSucceeded) return false;

            Picture[] verifiedPictures = TagLib.getPictures(duplicateFd(descriptor));
            if (verifiedPictures == null) return false;
            for (Picture picture : verifiedPictures) {
                if (picture != null && "Front Cover".equalsIgnoreCase(picture.getPictureType()) &&
                        Arrays.equals(artworkBytes, picture.getData())) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException | LinkageError failure) {
            rethrowSecurityException(failure);
            return false;
        }
    }

    private static void rethrowSecurityException(Throwable failure) {
        if (failure instanceof SecurityException) throw (SecurityException) failure;
    }

    private static int duplicateFd(ParcelFileDescriptor descriptor) throws IOException {
        // TagLib's normal native path adopts this duplicate in FileStream and closes it on destruction.
        return descriptor.dup().detachFd();
    }

    private static String preserveNumberTotal(String key, String value, HashMap<String, String[]> existing) {
        if (!("TRACKNUMBER".equals(key) || "DISCNUMBER".equals(key)) || value.contains("/")) {
            return value;
        }
        String[] oldValues = existing.get(key);
        if (oldValues == null || oldValues.length == 0) return value;
        String oldValue = oldValues[0];
        int slash = oldValue.indexOf('/');
        if (slash < 0 || slash == oldValue.length() - 1) return value;
        return value + oldValue.substring(slash);
    }

    private static String preserveDatePrecision(String key, String value, HashMap<String, String[]> existing) {
        if (!"DATE".equals(key) || value.length() != 4) return value;
        String[] oldValues = existing.get(key);
        if (oldValues == null || oldValues.length == 0) return value;
        String oldValue = oldValues[0];
        if (oldValue != null && oldValue.length() > 4 && oldValue.startsWith(value) &&
                !Character.isDigit(oldValue.charAt(4))) {
            return oldValue;
        }
        return value;
    }

    private static boolean matches(String key, String expected, String[] actualValues) {
        boolean expectedBlank = expected == null || expected.trim().isEmpty();
        if (expectedBlank) {
            if (actualValues == null) return true;
            for (String actual : actualValues) {
                if (actual != null && !actual.trim().isEmpty()) return false;
            }
            return true;
        }
        if (actualValues == null || actualValues.length == 0) return false;

        String actual = String.join(", ", actualValues).trim();
        if ("TRACKNUMBER".equals(key) || "DISCNUMBER".equals(key)) {
            return numberPart(actual).equals(numberPart(expected));
        }
        if ("DATE".equals(key)) {
            return actual.startsWith(expected);
        }
        if ("REPLAYGAIN_TRACK_GAIN".equals(key)) {
            Double expectedGain = parseGain(expected);
            Double actualGain = parseGain(actual);
            return expectedGain != null && actualGain != null && Math.abs(expectedGain - actualGain) < 0.02;
        }
        return actual.equals(expected.trim());
    }

    private static String numberPart(String value) {
        int slash = value.indexOf('/');
        String number = slash < 0 ? value : value.substring(0, slash);
        return number.trim().replaceFirst("^0+(?!$)", "");
    }

    private static Double parseGain(String value) {
        String clean = value.trim().replaceFirst("(?i)\\s*dB\\s*$", "").trim();
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
