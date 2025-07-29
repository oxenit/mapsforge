import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.reader.header.MapFileInfo;
import org.mapsforge.map.reader.header.MapFileException;
import org.mapsforge.core.model.Tag;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.datastore.MapReadResult;
import org.mapsforge.map.datastore.PoiWayBundle;
import org.mapsforge.map.datastore.PointOfInterest;
import org.mapsforge.map.datastore.Way;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class DeepMapAnalyzer {
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java DeepMapAnalyzer <map.map>");
            System.out.println("  Deep analysis of map file including all text data and label positions");
            return;
        }
        
        try {
            String filePath = args[0];
            System.out.println("=== DEEP MAPSFORGE MAP ANALYSIS ===");
            System.out.println("File: " + filePath);
            
            analyzeMapFileDeep(filePath);
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void analyzeMapFileDeep(String filePath) throws Exception {
        MapFile mapFile = new MapFile(new File(filePath));
        MapFileInfo info = mapFile.getMapFileInfo();
        
        System.out.println("\n=== BASIC INFO ===");
        System.out.println("File Version: " + info.fileVersion);
        System.out.println("Bounding Box: " + info.boundingBox);
        System.out.println("Created By: " + info.createdBy);
        System.out.println("POI Tags: " + info.poiTags.length);
        System.out.println("Way Tags: " + info.wayTags.length);
        
        System.out.println("\n=== ALL POI TAGS ===");
        for (int i = 0; i < info.poiTags.length; i++) {
            Tag tag = info.poiTags[i];
            System.out.println(i + ": " + tag.key + (tag.value != null ? "=" + tag.value : ""));
        }
        
        System.out.println("\n=== ALL WAY TAGS ===");
        for (int i = 0; i < info.wayTags.length; i++) {
            Tag tag = info.wayTags[i];
            System.out.println(i + ": " + tag.key + (tag.value != null ? "=" + tag.value : ""));
        }
        
        // Check which zoom levels actually contain data
        System.out.println("\n=== CHECKING AVAILABLE ZOOM LEVELS ===");
        
        // Get map bounds
        double minLat = info.boundingBox.minLatitude;
        double minLon = info.boundingBox.minLongitude;
        double maxLat = info.boundingBox.maxLatitude;
        double maxLon = info.boundingBox.maxLongitude;
        
        // Calculate center point for testing
        double centerLat = (minLat + maxLat) / 2;
        double centerLon = (minLon + maxLon) / 2;
        
        // Test all possible zoom levels (0-20)
        List<Integer> availableZooms = new ArrayList<>();
        for (int zoom = 0; zoom <= 20; zoom++) {
            try {
                int tileX = MercatorProjection.longitudeToTileX(centerLon, (byte) zoom);
                int tileY = MercatorProjection.latitudeToTileY(centerLat, (byte) zoom);
                Tile tile = new Tile(tileX, tileY, (byte) zoom, 256);
                MapReadResult result = mapFile.readMapData(tile);
                
                if (result.pois.size() > 0 || result.ways.size() > 0) {
                    availableZooms.add(zoom);
                    System.out.println("Zoom " + zoom + ": " + result.pois.size() + " POIs, " + result.ways.size() + " ways");
                }
            } catch (Exception e) {
                // Zoom level not available or error reading
            }
        }
        
        System.out.println("\nAvailable zoom levels: " + availableZooms);
        System.out.println("Min zoom: " + (availableZooms.isEmpty() ? "none" : Collections.min(availableZooms)));
        System.out.println("Max zoom: " + (availableZooms.isEmpty() ? "none" : Collections.max(availableZooms)));
        
        // Sample some tiles to look for actual data
        System.out.println("\n=== SAMPLING MAP DATA ===");
        
        // Sample tiles at available zoom levels (or fallback to default if none found)
        int[] zoomLevels = availableZooms.isEmpty() ? 
            new int[]{8, 10, 12, 14} : 
            availableZooms.stream().mapToInt(i -> i).toArray();
        Set<String> allTextFound = new HashSet<>();
        int totalPois = 0;
        int totalWays = 0;
        int waysWithNames = 0;
        int poisWithNames = 0;
        
        for (int zoom : zoomLevels) {
            System.out.println("\nZoom Level " + zoom + ":");
            
            int tileX = MercatorProjection.longitudeToTileX(centerLon, (byte) zoom);
            int tileY = MercatorProjection.latitudeToTileY(centerLat, (byte) zoom);
            
            try {
                Tile tile = new Tile(tileX, tileY, (byte) zoom, 256);
                MapReadResult result = mapFile.readMapData(tile);
                
                System.out.println("  Tile " + tileX + "," + tileY + ": " + 
                                 result.pois.size() + " POIs, " + result.ways.size() + " ways");
                
                // Analyze POIs
                for (PointOfInterest poi : result.pois) {
                    totalPois++;
                    if (poi.tags != null) {
                        for (Tag tag : poi.tags) {
                            if (tag.value != null && !tag.value.trim().isEmpty()) {
                                allTextFound.add("POI:" + tag.key + "=" + tag.value);
                                if (tag.key.contains("name") || tag.key.equals("place")) {
                                    poisWithNames++;
                                    System.out.println("    POI with name: " + tag.key + "=" + tag.value);
                                }
                            }
                        }
                    }
                }
                
                // Analyze Ways
                for (Way way : result.ways) {
                    totalWays++;
                    if (way.tags != null) {
                        boolean hasName = false;
                        for (Tag tag : way.tags) {
                            if (tag.value != null && !tag.value.trim().isEmpty()) {
                                allTextFound.add("WAY:" + tag.key + "=" + tag.value);
                                if (tag.key.contains("name") || tag.key.equals("place") || 
                                    tag.key.equals("ref") || tag.key.contains("addr")) {
                                    hasName = true;
                                    System.out.println("    Way with name/ref: " + tag.key + "=" + tag.value);
                                }
                            }
                        }
                        if (hasName) waysWithNames++;
                    }
                    
                    // Check for label position
                    if (way.labelPosition != null) {
                        System.out.println("    Way has label position: " + way.labelPosition);
                    }
                }
                
            } catch (Exception e) {
                System.out.println("  Error reading tile: " + e.getMessage());
            }
        }
        
        System.out.println("\n=== SUMMARY STATISTICS ===");
        System.out.println("Total POIs sampled: " + totalPois);
        System.out.println("Total Ways sampled: " + totalWays);
        System.out.println("POIs with names: " + poisWithNames);
        System.out.println("Ways with names: " + waysWithNames);
        System.out.println("Unique text strings found: " + allTextFound.size());
        
        System.out.println("\n=== ALL TEXT CONTENT FOUND ===");
        List<String> sortedText = new ArrayList<>(allTextFound);
        Collections.sort(sortedText);
        for (String text : sortedText) {
            System.out.println(text);
        }
        
        // Look for name-related patterns
        System.out.println("\n=== NAME-RELATED CONTENT ===");
        for (String text : sortedText) {
            if (text.toLowerCase().contains("name") || 
                text.toLowerCase().contains("place") || 
                text.toLowerCase().contains("city") ||
                text.toLowerCase().contains("town") ||
                text.toLowerCase().contains("village") ||
                text.toLowerCase().contains("ref")) {
                System.out.println("*** " + text);
            }
        }
        
        mapFile.close();
    }
} 