/*
 * JarSplitter
 * Copyright (c) 2016-2018.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.jarsplitter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.internal.Strings;

public class ConsoleTool {
    private static OutputStream NULL_OUTPUT = new OutputStream() {
        @Override public void write(int b) throws IOException {}
    };

    public static void main(String[] args) throws IOException {
        OptionParser parser = new OptionParser();
        // Shared arguments
        OptionSpec<File> inputO = parser.accepts("input").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> slimO = parser.accepts("slim").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> dataO = parser.accepts("data").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> extraO = parser.accepts("extra").withRequiredArg().ofType(File.class);
        OptionSpec<File> srgO = parser.accepts("srg").withRequiredArg().ofType(File.class);

        try {
            OptionSet options = parser.parse(args);

            File input = options.valueOf(inputO);
            File slim  = options.valueOf(slimO);
            File data  = options.valueOf(dataO);
            File extra = options.has(extraO) ? options.valueOf(extraO) : null;

            log("Splitter: ");
            log("  Input: " + input);
            log("  Slim:  " + slim);
            log("  Data:  " + data);
            log("  Extra: " + extra);

            Set<String> whitelist = new HashSet<>();

            if (options.has(srgO)) {
                for(File dir : options.valuesOf(srgO)) {
                    log("  SRG:   " + dir);
                    List<String> lines = Files.lines(Paths.get(dir.getAbsolutePath())).map(line -> line.split("#")[0]).filter(l -> !Strings.isNullOrEmpty(l.trim())).collect(Collectors.toList()); //Strip comments and empty lines
                    lines.stream()
                    .filter(line -> !line.startsWith("\t") || (line.indexOf(':') != -1 && line.startsWith("CL:"))) // Class lines only
                    .map(line -> line.indexOf(':') != -1 ? line.substring(4).split(" ") : line.split(" ")) //Convert to: OBF SRG
                    .filter(pts -> pts.length == 2 && !pts[0].endsWith("/")) //Skip packages
                    .forEach(pts -> whitelist.add(pts[0]));
                }
            }

            checkOutput(slim);
            checkOutput(data);
            if (extra != null) {
                if (whitelist.isEmpty()) throw new IllegalArgumentException("--extra argument specified with no --srg class list");
                checkOutput(extra);
            }

            log("Splitting: ");
            try (ZipInputStream zinput = new ZipInputStream(new FileInputStream(input));
                 ZipOutputStream zslim = new ZipOutputStream(new FileOutputStream(slim));
                 ZipOutputStream zdata = new ZipOutputStream(new FileOutputStream(data));
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
                       copy(entry, zinput, zdata);
                   }
               }
            }
        } catch (OptionException e) {
            parser.printHelpOn(System.out);
            e.printStackTrace();
        }
    }

    private static byte[] BUFFER = new byte[1024];
    private static void copy(ZipEntry entry, InputStream input, ZipOutputStream output) throws IOException {
        ZipEntry _new = new ZipEntry(entry.getName());
        _new.setTime(0);
        output.putNextEntry(_new);

        int read = -1;
        while ((read = input.read(BUFFER)) != -1)
            output.write(BUFFER, 0, read);
    }

    private static void checkOutput(File file) throws IOException {
        if (file == null) return;
        if (file.exists() && !file.delete()) throw new IOException("Could not delete file: " + file);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("Could not make prent folders: " + parent);
    }

    public static void log(String message) {
        System.out.println(message);
    }
}
