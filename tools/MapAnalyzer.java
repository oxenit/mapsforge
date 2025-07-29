import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * GPS Map Format Reverse Engineering Tool
 * 
 * Analyzes Mapsforge map files to understand their structure and identify
 * differences between standard and GPS-optimized formats.
 * 
 * Based on reverse engineering of FR1200_230310Q format
 * 
 * Usage:
 *   java MapAnalyzer <map-file>                    # Analyze single file
 *   java MapAnalyzer <map1> <map2>                 # Compare two files
 *   java MapAnalyzer --hex <map-file> [bytes]      # Hex dump analysis
 */
public class MapAnalyzer {
    
    private static final String MAGIC_BYTES = "mapsforge binary OSM";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            return;
        }
        
        try {
            if (args[0].equals("--hex")) {
                if (args.length < 2) {
                    System.err.println("Error: --hex requires a filename");
                    return;
                }
                int bytes = args.length > 2 ? Integer.parseInt(args[2]) : 512;
                hexDump(args[1], bytes);
            } else if (args.length == 1) {
                analyzeMapFile(args[0]);
            } else if (args.length == 2) {
                compareMapFiles(args[0], args[1]);
            } else {
                printUsage();
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void printUsage() {
        System.out.println("GPS Map Format Reverse Engineering Tool");
        System.out.println("=======================================");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java MapAnalyzer <map-file>           # Analyze single file");
        System.out.println("  java MapAnalyzer <map1> <map2>        # Compare two files");
        System.out.println("  java MapAnalyzer --hex <map-file> [bytes] # Hex dump (default: 512 bytes)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java MapAnalyzer gps-device-map.map");
        System.out.println("  java MapAnalyzer standard.map gps.map");
        System.out.println("  java MapAnalyzer --hex gps.map 200");
    }
    
    private static void analyzeMapFile(String filename) throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("GPS MAP ANALYSIS: " + filename);
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println();
        
        MapFileAnalysis analysis = analyzeFile(filename);
        printAnalysis(analysis);
        
        // GPS manufacturer detection
        detectGPSManufacturer(analysis);
        
        // Filtering assessment
        assessFiltering(analysis);
    }
    
    private static void compareMapFiles(String file1, String file2) throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("GPS MAP COMPARISON");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("File 1: " + file1);
        System.out.println("File 2: " + file2);
        System.out.println();
        
        MapFileAnalysis analysis1 = analyzeFile(file1);
        MapFileAnalysis analysis2 = analyzeFile(file2);
        
        printComparison(analysis1, analysis2, file1, file2);
    }
    
    private static void hexDump(String filename, int maxBytes) throws IOException {
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("HEX DUMP: " + filename + " (first " + maxBytes + " bytes)");
        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println();
        
        try (FileInputStream fis = new FileInputStream(filename)) {
            byte[] buffer = new byte[Math.min(maxBytes, 16)];
            int offset = 0;
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) > 0 && offset < maxBytes) {
                System.out.printf("%08X  ", offset);
                
                // Hex portion
                for (int i = 0; i < 16; i++) {
                    if (i < bytesRead) {
                        System.out.printf("%02X ", buffer[i] & 0xFF);
                    } else {
                        System.out.print("   ");
                    }
                    if (i == 7) System.out.print(" ");
                }
                
                System.out.print(" |");
                
                // ASCII portion
                for (int i = 0; i < bytesRead; i++) {
                    char c = (char) (buffer[i] & 0xFF);
                    System.out.print(Character.isISOControl(c) ? '.' : c);
                }
                
                System.out.println("|");
                offset += bytesRead;
                
                if (offset >= maxBytes) break;
                buffer = new byte[Math.min(maxBytes - offset, 16)];
            }
        }
    }
    
    private static MapFileAnalysis analyzeFile(String filename) throws IOException {
        MapFileAnalysis analysis = new MapFileAnalysis();
        
        try (FileInputStream fis = new FileInputStream(filename);
             FileChannel channel = fis.getChannel()) {
            
            analysis.filename = filename;
            analysis.fileSize = channel.size();
            
            // Read header manually for detailed analysis
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            channel.read(buffer);
            buffer.flip();
            
            // Magic bytes
            byte[] magicBytes = new byte[20];
            buffer.get(magicBytes);
            analysis.magicBytes = new String(magicBytes);
            analysis.validFormat = MAGIC_BYTES.equals(analysis.magicBytes.trim());
            
            // Header size
            analysis.headerSize = buffer.getInt();
            
            // File version
            analysis.fileVersion = buffer.getInt();
            
            // File size (in header)
            analysis.headerFileSize = buffer.getLong();
            
            // Map date
            analysis.mapDate = buffer.getLong();
            
            // Bounding box
            analysis.minLat = buffer.getInt() / 1000000.0;
            analysis.minLon = buffer.getInt() / 1000000.0;
            analysis.maxLat = buffer.getInt() / 1000000.0;
            analysis.maxLon = buffer.getInt() / 1000000.0;
            
            // Tile size
            analysis.tileSize = buffer.getShort();
            
            // Projection
            int projectionLength = readVBEU(buffer);
            byte[] projectionBytes = new byte[projectionLength];
            buffer.get(projectionBytes);
            analysis.projection = new String(projectionBytes);
            
            // Flags
            analysis.flags = buffer.get();
            analysis.debugFile = (analysis.flags & 0x80) != 0;
            
            // Optional fields
            if ((analysis.flags & 0x40) != 0) {
                analysis.startLat = buffer.getInt() / 1000000.0;
                analysis.startLon = buffer.getInt() / 1000000.0;
            }
            if ((analysis.flags & 0x20) != 0) {
                analysis.startZoom = buffer.get();
            }
            if ((analysis.flags & 0x10) != 0) {
                int langLength = readVBEU(buffer);
                byte[] langBytes = new byte[langLength];
                buffer.get(langBytes);
                analysis.language = new String(langBytes);
            }
            if ((analysis.flags & 0x08) != 0) {
                int commentLength = readVBEU(buffer);
                byte[] commentBytes = new byte[commentLength];
                buffer.get(commentBytes);
                analysis.comment = new String(commentBytes);
            }
            if ((analysis.flags & 0x04) != 0) {
                int creatorLength = readVBEU(buffer);
                byte[] creatorBytes = new byte[creatorLength];
                buffer.get(creatorBytes);
                analysis.createdBy = new String(creatorBytes);
            }
            
            // POI tags
            analysis.poiTagCount = buffer.getShort();
            analysis.poiTags = new ArrayList<>();
            for (int i = 0; i < analysis.poiTagCount; i++) {
                int tagLength = readVBEU(buffer);
                byte[] tagBytes = new byte[tagLength];
                buffer.get(tagBytes);
                analysis.poiTags.add(new String(tagBytes));
            }
            
            // Way tags  
            analysis.wayTagCount = buffer.getShort();
            analysis.wayTags = new ArrayList<>();
            for (int i = 0; i < analysis.wayTagCount; i++) {
                int tagLength = readVBEU(buffer);
                byte[] tagBytes = new byte[tagLength];
                buffer.get(tagBytes);
                analysis.wayTags.add(new String(tagBytes));
            }
            
            // Sub-files
            analysis.subFileCount = buffer.get();
            
        }
        
        return analysis;
    }
    
    private static int readVBEU(ByteBuffer buffer) {
        int result = 0;
        int shift = 0;
        byte b;
        
        do {
            b = buffer.get();
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        
        return result;
    }
    
    private static void printAnalysis(MapFileAnalysis analysis) {
        System.out.println("📁 FILE INFORMATION");
        System.out.println("   File: " + analysis.filename);
        System.out.printf("   Size: %,d bytes (%.2f MB)%n", analysis.fileSize, analysis.fileSize / 1024.0 / 1024.0);
        System.out.println();
        
        System.out.println("🔍 FORMAT ANALYSIS");
        System.out.println("   Magic bytes: '" + analysis.magicBytes.trim() + "'");
        System.out.println("   Valid format: " + (analysis.validFormat ? "✓ YES" : "✗ NO"));
        System.out.println("   File version: " + analysis.fileVersion);
        System.out.println("   Header size: " + analysis.headerSize + " bytes");
        System.out.println("   Projection: " + analysis.projection);
        System.out.println("   Debug file: " + (analysis.debugFile ? "YES" : "NO"));
        System.out.println();
        
        System.out.println("📅 METADATA");
        System.out.println("   Creation date: " + DATE_FORMAT.format(new Date(analysis.mapDate)));
        if (analysis.createdBy != null) {
            System.out.println("   Created by: " + analysis.createdBy);
        }
        if (analysis.comment != null) {
            System.out.println("   Comment: " + analysis.comment);
        }
        if (analysis.language != null) {
            System.out.println("   Language: " + analysis.language);
        }
        System.out.println();
        
        System.out.println("🗺️  MAP BOUNDS");
        System.out.printf("   Min: %.6f°, %.6f°%n", analysis.minLat, analysis.minLon);
        System.out.printf("   Max: %.6f°, %.6f°%n", analysis.maxLat, analysis.maxLon);
        System.out.println("   Tile size: " + analysis.tileSize + " pixels");
        if (analysis.startLat != null) {
            System.out.printf("   Start position: %.6f°, %.6f°%n", analysis.startLat, analysis.startLon);
        }
        if (analysis.startZoom != null) {
            System.out.println("   Start zoom: " + analysis.startZoom);
        }
        System.out.println();
        
        System.out.println("🏷️  TAG ANALYSIS");
        System.out.println("   POI tags: " + analysis.poiTagCount);
        System.out.println("   Way tags: " + analysis.wayTagCount);
        System.out.println("   Sub-files: " + analysis.subFileCount);
        System.out.println();
        
        if (analysis.poiTagCount > 0 && analysis.poiTagCount <= 50) {
            System.out.println("   POI tag list:");
            for (String tag : analysis.poiTags) {
                System.out.println("     • " + tag);
            }
            System.out.println();
        }
        
        if (analysis.wayTagCount > 0 && analysis.wayTagCount <= 50) {
            System.out.println("   Way tag list:");
            for (String tag : analysis.wayTags) {
                System.out.println("     • " + tag);
            }
            System.out.println();
        }
    }
    
    private static void detectGPSManufacturer(MapFileAnalysis analysis) {
        System.out.println("🔬 GPS MANUFACTURER DETECTION");
        
        if (analysis.createdBy != null) {
            String creator = analysis.createdBy.toLowerCase();
            if (creator.contains("fr1200")) {
                System.out.println("   🎯 DETECTED: French GPS manufacturer (FR1200 series)");
                System.out.println("   Pattern: Ultra-filtered Mapsforge format");
            } else if (creator.contains("garmin")) {
                System.out.println("   🎯 DETECTED: Garmin-style processing");
            } else if (creator.contains("tomtom")) {
                System.out.println("   🎯 DETECTED: TomTom-style processing");
            } else {
                System.out.println("   Creator: " + analysis.createdBy);
            }
        } else {
            System.out.println("   No creator information found");
        }
        
        // GPS optimization indicators
        boolean isGPSOptimized = analysis.poiTagCount == 0 && analysis.wayTagCount < 30;
        System.out.println("   GPS optimized: " + (isGPSOptimized ? "✓ YES" : "✗ NO"));
        System.out.println();
    }
    
    private static void assessFiltering(MapFileAnalysis analysis) {
        System.out.println("📊 FILTERING ASSESSMENT");
        
        if (analysis.poiTagCount == 0) {
            System.out.println("   🚫 COMPLETE POI ELIMINATION");
            System.out.println("      All amenities, shops, tourism removed");
            System.out.println("      File size reduction: ~50%");
        } else if (analysis.poiTagCount < 10) {
            System.out.println("   ⚡ AGGRESSIVE POI FILTERING");
            System.out.println("      Most POI categories removed");
        } else if (analysis.poiTagCount < 50) {
            System.out.println("   📉 MODERATE POI FILTERING");
        } else {
            System.out.println("   📈 STANDARD POI DENSITY");
        }
        
        if (analysis.wayTagCount < 20) {
            System.out.println("   🛣️  MINIMAL WAY TAGS (GPS navigation only)");
            System.out.println("      Likely highways + admin boundaries only");
        } else if (analysis.wayTagCount < 100) {
            System.out.println("   🗺️  MODERATE WAY FILTERING");
        } else {
            System.out.println("   🌍 COMPREHENSIVE WAY DATA");
        }
        
        if (analysis.subFileCount <= 2) {
            System.out.println("   📁 SIMPLIFIED STRUCTURE");
            System.out.println("      Reduced sub-file complexity");
        }
        
        // Overall assessment
        int optimizationScore = 0;
        if (analysis.poiTagCount == 0) optimizationScore += 3;
        if (analysis.wayTagCount < 30) optimizationScore += 2;
        if (analysis.subFileCount <= 2) optimizationScore += 1;
        
        System.out.println();
        System.out.print("   🎯 OPTIMIZATION LEVEL: ");
        if (optimizationScore >= 5) {
            System.out.println("EXTREME (GPS manufacturer style)");
        } else if (optimizationScore >= 3) {
            System.out.println("HIGH (Navigation focused)");
        } else if (optimizationScore >= 1) {
            System.out.println("MODERATE");
        } else {
            System.out.println("STANDARD (Full OSM data)");
        }
        System.out.println();
    }
    
    private static void printComparison(MapFileAnalysis a1, MapFileAnalysis a2, String file1, String file2) {
        System.out.println("📊 DETAILED COMPARISON");
        System.out.println();
        
        // File sizes
        System.out.printf("File size:      %,10d bytes    %,10d bytes", a1.fileSize, a2.fileSize);
        if (a1.fileSize != a2.fileSize) {
            double ratio = (double) a2.fileSize / a1.fileSize;
            if (ratio < 1) {
                System.out.printf("    📉 %.1f%% reduction", (1 - ratio) * 100);
            } else {
                System.out.printf("    📈 %.1f%% increase", (ratio - 1) * 100);
            }
        }
        System.out.println();
        
        // Versions
        System.out.printf("File version:   %10d        %10d", a1.fileVersion, a2.fileVersion);
        if (a1.fileVersion != a2.fileVersion) System.out.print("    ⚠️  Different versions");
        System.out.println();
        
        // Creator
        String creator1 = a1.createdBy != null ? a1.createdBy : "unknown";
        String creator2 = a2.createdBy != null ? a2.createdBy : "unknown";
        System.out.printf("Created by:     %-15s  %-15s", 
                         creator1.length() > 15 ? creator1.substring(0, 15) : creator1,
                         creator2.length() > 15 ? creator2.substring(0, 15) : creator2);
        if (!creator1.equals(creator2)) System.out.print("    ❗ Different creators");
        System.out.println();
        
        // POI tags
        System.out.printf("POI tags:       %10d        %10d", a1.poiTagCount, a2.poiTagCount);
        if (a1.poiTagCount != a2.poiTagCount) {
            int diff = Math.abs(a1.poiTagCount - a2.poiTagCount);
            System.out.printf("    📊 Δ%d", diff);
            if (a1.poiTagCount > a2.poiTagCount) {
                System.out.print(" (POI elimination detected)");
            }
        }
        System.out.println();
        
        // Way tags
        System.out.printf("Way tags:       %10d        %10d", a1.wayTagCount, a2.wayTagCount);
        if (a1.wayTagCount != a2.wayTagCount) {
            int diff = Math.abs(a1.wayTagCount - a2.wayTagCount);
            System.out.printf("    📊 Δ%d", diff);
            if (a1.wayTagCount > a2.wayTagCount) {
                System.out.print(" (Way filtering detected)");
            }
        }
        System.out.println();
        
        // Sub-files
        System.out.printf("Sub-files:      %10d        %10d", a1.subFileCount, a2.subFileCount);
        if (a1.subFileCount != a2.subFileCount) System.out.print("    📁 Different structure");
        System.out.println();
        
        // Debug mode
        System.out.printf("Debug mode:     %10s        %10s", a1.debugFile ? "YES" : "NO", a2.debugFile ? "YES" : "NO");
        if (a1.debugFile != a2.debugFile) System.out.print("    🔧 Different debug settings");
        System.out.println();
        
        System.out.println();
        
        // Tag differences
        if (a1.wayTagCount > 0 && a2.wayTagCount > 0 && a1.wayTagCount != a2.wayTagCount) {
            System.out.println("🏷️  WAY TAG DIFFERENCES");
            Set<String> tags1 = new HashSet<>(a1.wayTags);
            Set<String> tags2 = new HashSet<>(a2.wayTags);
            
            Set<String> onlyIn1 = new HashSet<>(tags1);
            onlyIn1.removeAll(tags2);
            
            Set<String> onlyIn2 = new HashSet<>(tags2);
            onlyIn2.removeAll(tags1);
            
            if (!onlyIn1.isEmpty()) {
                System.out.println("   Only in " + file1 + ":");
                onlyIn1.forEach(tag -> System.out.println("     • " + tag));
            }
            
            if (!onlyIn2.isEmpty()) {
                System.out.println("   Only in " + file2 + ":");
                onlyIn2.forEach(tag -> System.out.println("     • " + tag));
            }
            System.out.println();
        }
        
        // Overall assessment
        System.out.println("🎯 COMPARISON SUMMARY");
        
        if (a1.poiTagCount > 0 && a2.poiTagCount == 0) {
            System.out.println("   📊 " + file2 + " appears to be GPS-optimized (POI elimination)");
        } else if (a2.poiTagCount > 0 && a1.poiTagCount == 0) {
            System.out.println("   📊 " + file1 + " appears to be GPS-optimized (POI elimination)");
        }
        
        if (Math.abs(a1.wayTagCount - a2.wayTagCount) > 50) {
            System.out.println("   🛣️  Significant way filtering detected");
        }
        
        if (a1.fileSize > a2.fileSize * 2) {
            System.out.println("   💾 " + file2 + " is significantly smaller (likely filtered)");
        } else if (a2.fileSize > a1.fileSize * 2) {
            System.out.println("   💾 " + file1 + " is significantly smaller (likely filtered)");
        }
        
        System.out.println();
    }
    
    private static class MapFileAnalysis {
        String filename;
        long fileSize;
        String magicBytes;
        boolean validFormat;
        int headerSize;
        int fileVersion;
        long headerFileSize;
        long mapDate;
        double minLat, minLon, maxLat, maxLon;
        short tileSize;
        String projection;
        byte flags;
        boolean debugFile;
        Double startLat, startLon;
        Byte startZoom;
        String language;
        String comment;
        String createdBy;
        short poiTagCount;
        List<String> poiTags;
        short wayTagCount;
        List<String> wayTags;
        byte subFileCount;
    }
} 