#!/usr/bin/env python3

import sys
import struct
from pathlib import Path
import time
import math

# Add the wahoo tooling directory to the path
sys.path.append('wahooMapsCreator/tooling')

class DetailedMapFileReader:
    def __init__(self, filename):
        self.filename = filename
        self.mapFile = open(filename, "rb")
        self.header_parsed = False
        
    def close(self):
        self.mapFile.close()

    def readMagic(self):
        return self.mapFile.read(20).decode('utf-8')

    def readByte(self):
        return self.mapFile.read(1)[0]

    def readShort(self):
        b = self.mapFile.read(2)
        return b[0] << 8 | b[1]

    def readInt(self):
        b = self.mapFile.read(4)
        return b[0] << 24 | b[1] << 16 | b[2] << 8 | b[3]

    def readDegrees(self):
        return float(self.readInt())/1000000

    def readLong(self):
        b = self.mapFile.read(8)
        return b[0] << 56 | b[1] << 48 | b[2] << 40 | b[3] << 32 | b[4] << 24 | b[5] << 16 | b[6] << 8 | b[7]

    def readVBEU(self):
        res, shift, buf = 0, 0, 0
        while True:
            buf = int.from_bytes(self.mapFile.read(1), byteorder="big")
            if (buf & 0x80) == 0:
                break
            res |= (buf & 0x7f) << shift
            shift += 7
        return res | (buf << shift)

    def readString(self):
        return self.mapFile.read(self.readVBEU()).decode('utf-8')

    def readTags(self):
        amount = self.readShort()
        tags = []
        for _ in range(amount):
            tags.append(self.readString())
        return tags

    def readZoomIntervals(self):
        amount = self.readByte()
        intervals = []
        for _ in range(amount):
            values = (
                self.readByte(),  # base zoom
                self.readByte(),  # min zoom  
                self.readByte(),  # max zoom
                self.readLong(),  # position
                self.readLong(),  # size
            )
            intervals.append(values)
        return intervals
    
    def parse_header(self):
        """Parse the complete header and store information"""
        self.mapFile.seek(0)
        
        self.magic = self.readMagic()
        self.header_size = self.readInt()
        self.file_version = self.readInt()
        self.file_size = self.readLong()
        self.creation_date = self.readLong()
        
        # Bounding box
        self.min_lat = self.readDegrees()
        self.min_lon = self.readDegrees() 
        self.max_lat = self.readDegrees()
        self.max_lon = self.readDegrees()
        
        self.tile_size = self.readShort()
        self.projection = self.readString()
        
        flags = self.readByte()
        self.debug_file = (flags & 0x80) != 0
        self.has_start_position = (flags & 0x40) != 0
        self.has_start_zoom = (flags & 0x20) != 0
        self.has_languages = (flags & 0x10) != 0
        self.has_comment = (flags & 0x08) != 0
        self.has_created_by = (flags & 0x04) != 0
        
        # Optional fields
        if self.has_start_position:
            self.start_lat = self.readDegrees()
            self.start_lon = self.readDegrees()
        else:
            self.start_lat = self.start_lon = None
            
        if self.has_start_zoom:
            self.start_zoom = self.readByte()
        else:
            self.start_zoom = None
            
        if self.has_languages:
            self.languages = self.readString()
        else:
            self.languages = None
            
        if self.has_comment:
            self.comment = self.readString()
        else:
            self.comment = None
            
        if self.has_created_by:
            self.created_by = self.readString()
        else:
            self.created_by = None
        
        self.poi_tags = self.readTags()
        self.way_tags = self.readTags()
        self.zoom_intervals = self.readZoomIntervals()
        
        self.header_parsed = True

    def _deg2num(self, lat_deg, lon_deg, zoom):
        lat_rad = math.radians(lat_deg)
        n = 2.0 ** zoom
        xtile = int((lon_deg + 180.0) / 360.0 * n)
        ytile = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
        return (xtile, ytile)

    def _calculate_tile_count(self, base_zoom):
        min_x, min_y = self._deg2num(self.max_lat, self.min_lon, base_zoom)
        max_x, max_y = self._deg2num(self.min_lat, self.max_lon, base_zoom)
        return (max_x - min_x + 1) * (max_y - min_y + 1)

    def analyze_full(self):
        """Run all analysis steps and print a full report."""
        if not self.header_parsed:
            self.parse_header()
        
        print(f"\n{'='*25} FULL ANALYSIS REPORT: {self.filename} {'='*25}")

        print("\n=== HEADER DETAILS ===")
        print(f"  {'File Version:':<20} {self.file_version}")
        print(f"  {'File Size:':<20} {self.file_size:,} bytes")
        print(f"  {'Creation Date:':<20} {time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime(self.creation_date))}")
        print(f"  {'Projection:':<20} {self.projection}")
        print(f"  {'Created By:':<20} {self.created_by or 'N/A'}")
        print(f"  {'Comment:':<20} {self.comment or 'N/A'}")
        print(f"  {'Start Position:':<20} lat={self.start_lat}, lon={self.start_lon}" if self.start_lat else "N/A")
        print(f"  {'Start Zoom:':<20} {self.start_zoom or 'N/A'}")
        print(f"  {'Debug File:':<20} {self.debug_file}")

        print("\n=== FILE STRUCTURE OVERVIEW ===")
        print(f"  {'Component':<20} | {'Start Position':>15} | {'End Position':>15} | {'Size (Bytes)':>15}")
        print("-" * 70)
        print(f"  {'Header':<20} | {0:15,d} | {self.header_size-1:15,d} | {self.header_size:15,d}")
        total_size = self.header_size
        for i, (base, min_z, max_z, pos, size) in enumerate(self.zoom_intervals):
            print(f"  {'Zoom Interval ' + str(i):<20} | {pos:15,d} | {pos+size-1:15,d} | {size:15,d}")
            total_size += size
        print("-" * 70)
        print(f"  {'TOTAL PARSED':<20} | {'':>15} | {'':>15} | {total_size:15,d}")
        print(f"  {'FILE SIZE':<20} | {'':>15} | {'':>15} | {self.file_size:15,d}")


        print(f"\n=== ADVANCED ZOOM INTERVAL ANALYSIS ===")
        print(f"Total intervals: {len(self.zoom_intervals)}")
        print("Base | Min | Max | Size (MB) | Est. Tiles | Density (Bytes/Tile)")
        print("-----|-----|-----|-----------|------------|----------------------")
        
        for base, min_z, max_z, pos, size in self.zoom_intervals:
            size_mb = size / (1024 * 1024)
            tile_count = self._calculate_tile_count(base)
            density = size / tile_count if tile_count > 0 else 0
            print(f" {base:2d}  | {min_z:2d}  | {max_z:2d}  | {size_mb:9.1f} | {tile_count:10,d} | {density:20,.0f}")
        
        if len(self.zoom_intervals) > 0:
            self.sample_tile_data(0, 3)
        
        print("\n" + "="*80)

    def sample_tile_data(self, zoom_interval_idx=0, num_samples=5):
        """Sample actual tile data from a zoom interval"""
        if not self.header_parsed:
            self.parse_header()
            
        if zoom_interval_idx >= len(self.zoom_intervals):
            print(f"Invalid zoom interval index: {zoom_interval_idx}")
            return
            
        base, min_z, max_z, pos, size = self.zoom_intervals[zoom_interval_idx]
        
        print(f"\n=== TILE-LEVEL OPTIMIZATIONS (Sample from Interval {zoom_interval_idx}: zoom {min_z}-{max_z}) ===")
        
        self.mapFile.seek(pos)
        
        if self.debug_file:
            print(f"Index signature: {self.mapFile.read(16)}")
        
        for sample in range(min(num_samples, 5)):
            try:
                current_pos = self.mapFile.tell()
                if current_pos >= pos + size: break
                
                if self.mapFile.tell() + 5 <= pos + size:
                    tile_data = self.mapFile.read(5)
                    if len(tile_data) == 5:
                        tile_int = int.from_bytes(tile_data, byteorder='big')
                        is_water = (tile_int & 0x8000000000) != 0
                        tile_offset = tile_int & 0x7FFFFFFFFF
                        print(f"  Sample {sample + 1}: Tile Index Entry shows Is_Water={is_water}, Data Offset={tile_offset}")
                    else: break
                else: break
            except Exception as e:
                print(f"  Error reading sample {sample + 1}: {e}")
                break

    def compare_with(self, other_file):
        """Compare this map file with another"""
        if not self.header_parsed:
            self.parse_header()
            
        other = DetailedMapFileReader(other_file)
        other.parse_header()
        
        print(f"\n{'='*30} DETAILED COMPARISON {'='*30}")
        
        print(f"{'Metric':<20} | {'Your File':<35} | {'GPS File':<35}")
        print("-" * 95)
        
        print(f"{'File Size (MB)':<20} | {self.file_size/(1024*1024):<35.1f} | {other.file_size/(1024*1024):<35.1f}")
        print(f"{'Created By':<20} | {self.created_by or 'N/A':<35} | {other.created_by or 'N/A':<35}")
        print(f"{'Way Tags':<20} | {len(self.way_tags):<35} | {len(other.way_tags):<35}")
        print(f"{'Zoom Intervals':<20} | {len(self.zoom_intervals):<35} | {len(other.zoom_intervals):<35}")

        print(f"\nWAY TAG DIFFERENCES:")
        print("  In your file but not GPS:", set(self.way_tags) - set(other.way_tags))
        print("  In GPS file but not yours:", set(other.way_tags) - set(self.way_tags))
        
        print(f"\nZOOM LEVEL COVERAGE:")
        your_zooms = set().union(*(range(min_z, max_z + 1) for _, min_z, max_z, _, _ in self.zoom_intervals))
        gps_zooms = set().union(*(range(min_z, max_z + 1) for _, min_z, max_z, _, _ in other.zoom_intervals))
        print(f"  Your file covers zooms ({len(your_zooms)} levels): {sorted(your_zooms)}")
        print(f"  GPS file covers zooms ({len(gps_zooms)} levels): {sorted(gps_zooms)}")
        
        other.close()

def main():
    if len(sys.argv) != 3:
        print("Usage: python3 map-detail-analyzer.py <your_file.map> <gps_file.map>")
        sys.exit(1)
    
    your_file = sys.argv[1]
    gps_file = sys.argv[2]
    
    # Analyze your file
    your_map = DetailedMapFileReader(your_file)
    your_map.analyze_full()
    
    # Analyze GPS file
    gps_map = DetailedMapFileReader(gps_file)
    gps_map.analyze_full()
    
    # Compare files
    your_map.compare_with(gps_file)
    
    your_map.close()
    gps_map.close()

if __name__ == "__main__":
    main() 