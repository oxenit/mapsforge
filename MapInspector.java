/*
 * MapInspector - unified Mapsforge .map analysis utility
 *
 * Features (selected by command-line flags):
 *   --header <file>            : print full header / sub-file table
 *   --deep   <file> [zoom-max] : sample real content per zoom (0..zoomMax, default 8)
 *   --hex    <file> [bytes]    : dump first N bytes as hex (default 256)
 *   --compare <file1> <file2>  : header-level diff
 *
 * Build:
 *   javac -cp "mapsforge-core.jar:mapsforge-map.jar:mapsforge-map-reader.jar" MapInspector.java
 *
 * Author: merged from MapAnalyzer.java, MapDetailAnalyzer.java, DeepMapAnalyzer.java
 */
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.reader.header.MapFileInfo;
import org.mapsforge.map.reader.header.SubFileParameter;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PointOfInterest;
import org.mapsforge.map.datastore.Way;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MapInspector {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        try {
            switch (args[0]) {
                case "--header":
                    if (args.length < 2) { printUsage(); return; }
                    printHeader(args[1]);
                    break;
                case "--hex":
                    if (args.length < 2) { printUsage(); return; }
                    int bytes = args.length > 2 ? Integer.parseInt(args[2]) : 256;
                    hexDump(args[1], bytes);
                    break;
                case "--compare":
                    if (args.length < 3) { printUsage(); return; }
                    compareHeaders(args[1], args[2]);
                    break;
                case "--deep":
                    if (args.length < 2) { printUsage(); return; }
                    int zoomMax = args.length > 2 ? Integer.parseInt(args[2]) : 8;
                    deepScan(args[1], zoomMax);
                    break;
                default:
                    // If arguments are just filenames (1 or 2) run full analysis automatically
                    if (args.length == 1 && new File(args[0]).exists()) {
                        String f = args[0];
                        printHeader(f);
                        System.out.println();
                        hexDump(f, 256);
                        System.out.println();
                        deepScan(f, 8);
                    } else if (args.length == 2 && new File(args[0]).exists() && new File(args[1]).exists()) {
                        // run header on both then diff
                        printHeader(args[0]);
                        System.out.println();
                        printHeader(args[1]);
                        System.out.println();
                        compareHeaders(args[0], args[1]);
                    } else {
                        printUsage();
                    }
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printUsage() {
        System.out.println("MapInspector - unified Mapsforge .map analyzer\n" +
                "Usage:\n" +
                "  --header  <file>            print full header\n" +
                "  --deep    <file> [zoomMax]  sample map content (default zoomMax=8)\n" +
                "  --hex     <file> [bytes]    hex dump (default 256)\n" +
                "  --compare <file1> <file2>   diff headers\n" +
                "When no flag is given but a filename is, --header is assumed.");
    }

    /* ===================================================== */
    // HEADER MODE
    private static void printHeader(String file) throws IOException {
        MapFile map = new MapFile(new File(file));
        MapFileInfo info = map.getMapFileInfo();
        System.out.println("=================================================");
        System.out.println(" Detailed Map Analysis for: " + new File(file).getName());
        System.out.println("=================================================");
        System.out.println("File Version: " + info.fileVersion);
        System.out.println("File Size: " + Files.size(Paths.get(file)) + " bytes");
        System.out.println("Creation Date: " + new Date(info.mapDate));
        System.out.println("Projection: " + info.projectionName);
        System.out.println("Created By: " + (info.createdBy != null ? info.createdBy : "N/A"));
        System.out.println("Comment: " + (info.comment != null ? info.comment : "N/A"));
        System.out.println("Preferred Languages: " + (info.languagesPreference != null ? info.languagesPreference : "N/A"));
        if (info.startPosition != null) {
            System.out.println("Start Position: " + info.startPosition.latitude + ", " + info.startPosition.longitude);
        } else {
            System.out.println("Start Position: N/A");
        }
        System.out.println("Start Zoom Level: " + (info.startZoomLevel != null ? info.startZoomLevel : "N/A"));
        System.out.println("Bounding Box: minLatitude=" + info.boundingBox.minLatitude + ", minLongitude=" + info.boundingBox.minLongitude +
                           ", maxLatitude=" + info.boundingBox.maxLatitude + ", maxLongitude=" + info.boundingBox.maxLongitude);
        System.out.println("Tile Size: " + info.tilePixelSize + "x" + info.tilePixelSize);
        System.out.println("Is Debug File: " + info.debugFile);
        System.out.println("POI Tags (" + info.poiTags.length + "):");
        for (Tag t : info.poiTags) {
            System.out.println("  - " + t.key + (t.value != null ? "=" + t.value : ""));
        }
        System.out.println("Way Tags (" + info.wayTags.length + "):");
        for (Tag t : info.wayTags) {
            System.out.println("  - " + t.key + (t.value != null ? "=" + t.value : ""));
        }
        // sub-files
        System.out.println("\n--- Sub-File Details ---");
        System.out.println("Number of Sub-Files: " + info.numberOfSubFiles);
        for (byte z = 0; z <= info.zoomLevelMax; z++) {
            SubFileParameter sub = map.getMapFileHeader().getSubFileParameter(z);
            if (sub != null) {
                System.out.println("[Zoom " + z + "] size=" + sub.subFileSize + " start=" + sub.startAddress +
                        " blocks=" + sub.numberOfBlocks);
            }
        }
        map.close();
    }

    private static void printFirstTags(Tag[] tags, int limit) {
        int n = Math.min(tags.length, limit);
        for (int i = 0; i < n; i++) {
            System.out.print(tags[i].key + (tags[i].value != null ? "=" + tags[i].value : "") + (i < n-1 ? ", " : "\n"));
        }
    }

    /* ===================================================== */
    // HEX MODE
    private static void hexDump(String filename, int maxBytes) throws IOException {
        System.out.println("HEX DUMP (" + maxBytes + " bytes): " + filename);
        try (FileInputStream fis = new FileInputStream(filename)) {
            byte[] buf = new byte[maxBytes];
            int n = fis.read(buf);
            for (int i = 0; i < n; i++) {
                if (i % 16 == 0) System.out.printf("%04X: ", i);
                System.out.printf("%02X ", buf[i]);
                if ((i+1) % 16 == 0) System.out.println();
            }
            System.out.println();
        }
    }

    /* ===================================================== */
    // COMPARE MODE
    private static void compareHeaders(String f1, String f2) throws IOException {
        MapFileInfo i1 = new MapFile(new File(f1)).getMapFileInfo();
        MapFileInfo i2 = new MapFile(new File(f2)).getMapFileInfo();
        System.out.println("Field                           | " + f1 + " | " + f2 + " | Same?");
        System.out.println("--------------------------------+------------------------------+------------------------------+------");
        cmp("FileVersion", i1.fileVersion, i2.fileVersion);
        cmp("TileSize", i1.tilePixelSize, i2.tilePixelSize);
        cmp("Projection", i1.projectionName, i2.projectionName);
        cmp("CreatedBy", i1.createdBy, i2.createdBy);
        cmp("POI tags", i1.poiTags.length, i2.poiTags.length);
        cmp("Way tags", i1.wayTags.length, i2.wayTags.length);
        cmp("ZoomMin", i1.zoomLevelMin, i2.zoomLevelMin);
        cmp("ZoomMax", i1.zoomLevelMax, i2.zoomLevelMax);
    }

    private static void cmp(String field, Object a, Object b) {
        boolean same = Objects.equals(a, b);
        System.out.printf("%-30s | %-28s | %-28s | %s\n", field, a, b, same ? "✔" : "✖");
    }

    /* ===================================================== */
    // DEEP MODE
    private static void deepScan(String file, int zoomMax) throws Exception {
        MapFile map = new MapFile(new File(file));
        MapFileInfo info = map.getMapFileInfo();
        BoundingBox bb = info.boundingBox;
        double centerLat = (bb.minLatitude + bb.maxLatitude) / 2.0;
        double centerLon = (bb.minLongitude + bb.maxLongitude) / 2.0;

        System.out.println("Deep scan around center " + centerLat + "," + centerLon);
        int totalPois=0, totalWays=0;
        for (int z = 0; z <= zoomMax; z++) {
            int x = MercatorProjection.longitudeToTileX(centerLon, (byte) z);
            int y = MercatorProjection.latitudeToTileY(centerLat, (byte) z);
            Tile tile = new Tile(x, y, (byte) z, info.tilePixelSize);
            MapReadResult res = map.readMapData(tile);
            if (res == null) continue;
            if (res.pois.size() == 0 && res.ways.size() == 0) continue;
            System.out.println("Zoom " + z + " : " + res.pois.size() + " POIs, " + res.ways.size() + " ways");
            totalPois += res.pois.size();
            totalWays += res.ways.size();
        }
        System.out.println("Total sampled POIs: " + totalPois + ", ways: " + totalWays);
        map.close();
    }

    /* ===================================================== */
    // helpers
    private static String readMagicBytes(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buf = new byte[20];
            int n=fis.read(buf);
            return new String(buf,0,n,"UTF-8");
        }
    }
} 