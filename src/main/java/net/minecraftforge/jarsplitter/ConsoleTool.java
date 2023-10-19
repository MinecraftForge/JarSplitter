/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.jarsplitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import net.minecraftforge.srgutils.IMappingFile;

public class ConsoleTool {
    private static final OutputStream NULL_OUTPUT = new OutputStream() {
        @Override public void write(int b) throws IOException {}
    };

    public static void main(String[] args) throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT")); //Fix Java stupidity that causes timestamps in zips to depend on user's timezone!
        OptionParser parser = new OptionParser();
        // Shared arguments
        OptionSpec<File> inputO = parser.accepts("input").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> slimO = parser.accepts("slim").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> dataO = parser.accepts("data").withRequiredArg().ofType(File.class);
        OptionSpec<File> extraO = parser.accepts("extra").withRequiredArg().ofType(File.class);
        OptionSpec<File> srgO = parser.accepts("srg").withRequiredArg().ofType(File.class);

        try {
            OptionSet options = parser.parse(args);

            File input = options.valueOf(inputO);
            File slim  = options.valueOf(slimO);
            File data  = options.has(dataO) ? options.valueOf(dataO) : null;
            File extra = options.has(extraO) ? options.valueOf(extraO) : null;
            boolean merge = data == null;

            log("Splitter: ");
            log("  Input:    " + input);
            log("  Slim:     " + slim);
            log("  Data:     " + data);
            log("  Extra:    " + extra);
            if (merge)
                log("  Including data in extra");

            Set<String> whitelist = new HashSet<>();

            if (options.has(srgO)) {
                for(File dir : options.valuesOf(srgO)) {
                    log("  SRG:   " + dir);
                    IMappingFile srg = IMappingFile.load(dir);
                    srg.getClasses().forEach(c -> whitelist.add(c.getOriginal()));
                }
            }

            String inputSha = sha1(input, true);
            String srgSha = sha1(whitelist);
            log("  InputSha: " + inputSha);
            log("  SrgSha:   " + srgSha);

            slim = checkOutput("Slim", slim, inputSha, srgSha);
            data = checkOutput("Data", data, inputSha, srgSha);
            if (extra != null) {
                if (whitelist.isEmpty()) throw new IllegalArgumentException("--extra argument specified with no --srg class list");
                extra = checkOutput("Extra", extra, inputSha, srgSha, merge ? "\nMerge: true" : null);
            } else if (merge) {
                throw new IllegalArgumentException("You must specify --extra if you do not specify --data");
            }

            if (slim == null && data == null && extra == null) {
                log("All files up to date");
                return;
            }

            log("Splitting: ");
            try (ZipInputStream zinput = new ZipInputStream(new FileInputStream(input));
                 ZipOutputStream zslim = new ZipOutputStream(slim == null ? NULL_OUTPUT : new FileOutputStream(slim));
                 ZipOutputStream zdata = new ZipOutputStream(data == null ? NULL_OUTPUT : new FileOutputStream(data));
                 ZipOutputStream zextra = new ZipOutputStream(extra == null ? NULL_OUTPUT : new FileOutputStream(extra))) {

               ZipEntry entry;
               while ((entry = zinput.getNextEntry()) != null) {
                   if (entry.getName().endsWith(".class")) {
                       String key = entry.getName().substring(0, entry.getName().length() - 6); //String .class

                       if (whitelist.isEmpty() || whitelist.contains(key)) {
                           log("  Slim  " + entry.getName());
                           copy(entry, zinput, zslim);
                       } else {
                           log("  Extra " + entry.getName());
                           copy(entry, zinput, zextra);
                       }
                   } else {
                       log("  Data  " + entry.getName());
                       copy(entry, zinput, merge ? zextra : zdata);
                   }
               }
            }
            writeCache(slim, inputSha, srgSha);
            writeCache(data, inputSha, srgSha);
            writeCache(extra, inputSha, srgSha);
        } catch (OptionException e) {
            parser.printHelpOn(System.out);
            e.printStackTrace();
        }
    }

    private static byte[] BUFFER = new byte[1024];
    private static void copy(ZipEntry entry, InputStream input, ZipOutputStream output) throws IOException {
        ZipEntry _new = new ZipEntry(entry.getName());
        _new.setTime(628041600000L); //Java8 screws up on 0 time, so use another static time.
        output.putNextEntry(_new);

        int read = -1;
        while ((read = input.read(BUFFER)) != -1)
            output.write(BUFFER, 0, read);
    }

    private static void writeCache(File file, String inputSha, String srgSha) throws IOException {
        if (file == null) return;

        File cacheFile = new File(file.getAbsolutePath() + ".cache");
        byte[] cache = ("Input: " + inputSha + "\n" +
                        "Srg: " + srgSha + "\n" +
                        "Output: " + sha1(file, false)).getBytes();
        Files.write(cacheFile.toPath(), cache);
    }

    private static File checkOutput(String name, File file, String inputSha, String srgSha) throws IOException {
        return checkOutput(name, file, inputSha, srgSha, null);
    }

    private static File checkOutput(String name, File file, String inputSha, String srgSha, String extra) throws IOException {
        if (file == null) return null;

        file = file.getCanonicalFile();
        File cacheFile = new File(file.getAbsolutePath() + ".cache");
        if (cacheFile.exists()) {
            byte[] data = Files.readAllBytes(cacheFile.toPath());
            byte[] cache = ("Input: " + inputSha + "\n" +
                            "Srg: " + srgSha + "\n" +
                            "Output: " + sha1(file, false) +
                            (extra == null ? "" : extra)).getBytes(); // Reading from disc is less costly/destructive then writing. So we can verify the output hasn't changed.

            if (Arrays.equals(cache, data) && file.exists()) {
                log("  " + name + " Cache Hit");
                return null;
            }
            log("  " + name + " Cache Miss");
            if (!cacheFile.delete()) throw new IOException("Could not delete file: " + cacheFile);
        }

        if (file.exists() && !file.delete()) throw new IOException("Could not delete file: " + file);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Could not make prent folders: " + parent);
        return file;
    }

    public static void log(String message) {
        System.out.println(message);
    }

    private static String sha1(Set<String> data) throws IOException {
        if (data.isEmpty())
            return "empty";

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            data.stream().sorted().forEach(e -> digest.update(e.getBytes()));
            return new BigInteger(1, digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha1(File path, boolean allowCache) throws IOException {
        if (!path.exists())
            return "missing";

        File shaFile = new File(path.getAbsolutePath() + ".sha");
        if (allowCache && shaFile.exists()) {
            return Files.lines(shaFile.toPath()).findFirst().orElse("");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try(InputStream input = new FileInputStream(path)) {
                int read = -1;
                while ((read = input.read(BUFFER)) != -1)
                    digest.update(BUFFER, 0, read);
                return new BigInteger(1, digest.digest()).toString(16);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
