import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.reader.header.MapFileInfo;
import org.mapsforge.map.reader.header.MapFileException;
import org.mapsforge.core.model.Tag;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MapAnalyzer {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java MapAnalyzer <map1.map> [map2.map]");
            System.out.println("  Analyzes map file format and compares if two files provided");
            return;
        }
        
        try {
            System.out.println("=== MAPSFORGE MAP FILE ANALYZER ===\n");
            
            // Analyze first map
            String file1 = args[0];
            System.out.println("Analyzing: " + file1);
            AnalysisResult result1 = analyzeMapFile(file1);
            printAnalysis(result1);
            
            // If second map provided, compare
            if (args.length > 1) {
                String file2 = args[1];
                System.out.println("\n" + "=".repeat(50));
                System.out.println("Analyzing: " + file2);
                AnalysisResult result2 = analyzeMapFile(file2);
                printAnalysis(result2);
                
                System.out.println("\n" + "=".repeat(50));
                System.out.println("COMPARISON RESULTS:");
                compareResults(result1, result2);
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static AnalysisResult analyzeMapFile(String filePath) throws Exception {
        AnalysisResult result = new AnalysisResult();
        result.filePath = filePath;
        result.fileSize = Files.size(Paths.get(filePath));
        
        // Try to parse with Mapsforge
        try {
            MapFile mapFile = new MapFile(new File(filePath));
            MapFileInfo info = mapFile.getMapFileInfo();
            
            result.isValidMapsforge = true;
            result.fileVersion = info.fileVersion;
            result.boundingBox = info.boundingBox.toString();
            result.tilePixelSize = info.tilePixelSize;
            result.projectionName = info.projectionName;
            result.mapDate = info.mapDate;
            result.numberOfSubFiles = info.numberOfSubFiles;
            result.debugFile = info.debugFile;
            result.startPosition = info.startPosition != null ? info.startPosition.toString() : null;
            result.startZoomLevel = info.startZoomLevel;
            result.languagesPreference = info.languagesPreference;
            result.comment = info.comment;
            result.createdBy = info.createdBy;
            result.poiTagCount = info.poiTags.length;
            result.wayTagCount = info.wayTags.length;
            
            // Store first few POI and Way tags for comparison
            result.firstPoiTags = getFirstTags(info.poiTags, 5);
            result.firstWayTags = getFirstTags(info.wayTags, 5);
            
            mapFile.close();
        } catch (MapFileException e) {
            result.isValidMapsforge = false;
            result.mapsforgeError = e.getMessage();
        }
        
        // Read raw file header
        result.magicBytes = readMagicBytes(filePath);
        result.headerHex = readHeaderHex(filePath, 200); // First 200 bytes
        
        return result;
    }
    
    private static String[] getFirstTags(Tag[] tags, int count) {
        int limit = Math.min(tags.length, count);
        String[] result = new String[limit];
        for (int i = 0; i < limit; i++) {
            result[i] = tags[i].key + (tags[i].value != null ? "=" + tags[i].value : "");
        }
        return result;
    }
    
    private static String readMagicBytes(String filePath) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[20]; // "mapsforge binary OSM" is 20 bytes
            int bytesRead = fis.read(buffer);
            return new String(buffer, 0, bytesRead, "UTF-8");
        }
    }
    
    private static String readHeaderHex(String filePath, int bytes) throws IOException {
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[bytes];
            int bytesRead = fis.read(buffer);
            
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < bytesRead; i++) {
                if (i % 16 == 0) hex.append(String.format("%04X: ", i));
                hex.append(String.format("%02X ", buffer[i] & 0xFF));
                if ((i + 1) % 16 == 0) hex.append("\n");
            }
            return hex.toString();
        }
    }
    
    private static void printAnalysis(AnalysisResult result) {
        System.out.println("File: " + result.filePath);
        System.out.println("Size: " + result.fileSize + " bytes");
        System.out.println("Magic Bytes: " + result.magicBytes);
        System.out.println("Valid Mapsforge: " + result.isValidMapsforge);
        
        if (result.isValidMapsforge) {
            System.out.println("  File Version: " + result.fileVersion);
            System.out.println("  Bounding Box: " + result.boundingBox);
            System.out.println("  Tile Size: " + result.tilePixelSize);
            System.out.println("  Projection: " + result.projectionName);
            System.out.println("  Map Date: " + new java.util.Date(result.mapDate));
            System.out.println("  Sub-files: " + result.numberOfSubFiles);
            System.out.println("  Debug File: " + result.debugFile);
            System.out.println("  Start Position: " + result.startPosition);
            System.out.println("  Start Zoom: " + result.startZoomLevel);
            System.out.println("  Languages: " + result.languagesPreference);
            System.out.println("  Comment: " + result.comment);
            System.out.println("  Created By: " + result.createdBy);
            System.out.println("  POI Tags: " + result.poiTagCount);
            System.out.println("  Way Tags: " + result.wayTagCount);
            
            if (result.firstPoiTags.length > 0) {
                System.out.println("  First POI Tags: " + String.join(", ", result.firstPoiTags));
            }
            if (result.firstWayTags.length > 0) {
                System.out.println("  First Way Tags: " + String.join(", ", result.firstWayTags));
            }
        } else {
            System.out.println("  Error: " + result.mapsforgeError);
        }
        
        System.out.println("\nRaw Header (first 200 bytes):");
        System.out.println(result.headerHex);
    }
    
    private static void compareResults(AnalysisResult r1, AnalysisResult r2) {
        System.out.println("File Format Compatibility:");
        System.out.println("  File 1 valid: " + r1.isValidMapsforge);
        System.out.println("  File 2 valid: " + r2.isValidMapsforge);
        
        if (r1.isValidMapsforge && r2.isValidMapsforge) {
            System.out.println("\nFormat Differences:");
            compare("File Version", r1.fileVersion, r2.fileVersion);
            compare("Tile Size", r1.tilePixelSize, r2.tilePixelSize);
            compare("Projection", r1.projectionName, r2.projectionName);
            compare("Sub-files", r1.numberOfSubFiles, r2.numberOfSubFiles);
            compare("Debug File", r1.debugFile, r2.debugFile);
            compare("POI Tag Count", r1.poiTagCount, r2.poiTagCount);
            compare("Way Tag Count", r1.wayTagCount, r2.wayTagCount);
            compare("Created By", r1.createdBy, r2.createdBy);
        }
        
        System.out.println("\nMagic Bytes Comparison:");
        System.out.println("  File 1: '" + r1.magicBytes + "'");
        System.out.println("  File 2: '" + r2.magicBytes + "'");
        System.out.println("  Match: " + r1.magicBytes.equals(r2.magicBytes));
    }
    
    private static void compare(String field, Object val1, Object val2) {
        boolean same = (val1 == null ? val2 == null : val1.equals(val2));
        System.out.println("  " + field + ": " + (same ? "SAME" : "DIFFERENT") + 
                          " (" + val1 + " vs " + val2 + ")");
    }
    
    static class AnalysisResult {
        String filePath;
        long fileSize;
        String magicBytes;
        String headerHex;
        boolean isValidMapsforge;
        String mapsforgeError;
        
        // Mapsforge fields
        int fileVersion;
        String boundingBox;
        int tilePixelSize;
        String projectionName;
        long mapDate;
        int numberOfSubFiles;
        boolean debugFile;
        String startPosition;
        Byte startZoomLevel;
        String languagesPreference;
        String comment;
        String createdBy;
        int poiTagCount;
        int wayTagCount;
        String[] firstPoiTags;
        String[] firstWayTags;
    }
} 