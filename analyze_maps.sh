#!/bin/bash

# This script automates the analysis of Mapsforge .map files using MapDetailAnalyzer.
# It processes a directory of map files, runs the analyzer on each, and saves
# the output into a comprehensive CSV file for easy review.
#
# The script is currently configured to analyze files matching the "FR*.map"
# pattern, based on the discovery of a proprietary naming convention used for
# these maps. The convention is as follows:
# <CountryCode><RegionCode><Constant_00><Date_YYMMDD><GridIdentifier>.map
#
# The GridIdentifier is a "black box" code from a closed-source system and
# cannot be decoded from this codebase. This script helps in correlating the
# identifier with the map's actual geographic data by displaying them side-by-side.

# --- Configuration ---
# Directory containing the .map files
MAPS_DIRECTORY="/Users/greg/Desktop/Maps"

# Path to the directory containing the MapDetailAnalyzer class
ANALYZER_PATH="tools"

# Output CSV file path
CSV_OUTPUT_FILE="french_maps_analysis.csv"

# Paths to required JAR files
CORE_JAR="mapsforge-core/build/libs/mapsforge-core-master-SNAPSHOT.jar"
READER_JAR="mapsforge-map-reader/build/libs/mapsforge-map-reader-master-SNAPSHOT.jar"
MAP_JAR="mapsforge-map/build/libs/mapsforge-map-master-SNAPSHOT.jar"

# Full classpath for the analyzer
FULL_CLASSPATH="$ANALYZER_PATH:$CORE_JAR:$READER_JAR:$MAP_JAR"
# --- End Configuration ---

# Check if the maps directory exists
if [ ! -d "$MAPS_DIRECTORY" ]; then
  echo "Error: Maps directory not found at $MAPS_DIRECTORY"
  exit 1
fi

# Create/clear the CSV file and write the header
echo "Filename,Version,Size,Date,Projection,Created By,Bounding Box,Start Pos,Zoom,Langs,Comment,Tile Size,Debug,POIs,Ways,Sub-Files" > "$CSV_OUTPUT_FILE"

# Process files and append to CSV
find "$MAPS_DIRECTORY" -name "FR*.map" | while read -r map_file; do
  # Run the analyzer and capture the output
  output=$(java -cp "$FULL_CLASSPATH" MapDetailAnalyzer "$map_file")

  # Extract information using awk for robustness, and removing newlines.
  filename=$(basename "$map_file")
  version=$(echo "$output" | awk '/^File Version:/ {print $3}' | tr -d '\n\r')
  file_size=$(echo "$output" | awk -F': ' '/^File Size:/ {print $2}' | tr -d '\n\r')
  date=$(echo "$output" | awk -F': ' '/^Creation Date:/ {print $2}' | tr -d '\n\r')
  projection=$(echo "$output" | awk -F': ' '/^Projection:/ {print $2}' | tr -d '\n\r')
  created_by=$(echo "$output" | awk -F': ' '/^Created By:/ {print $2}' | tr -d '\n\r')
  bbox=$(echo "$output" | awk -F': ' '/^Bounding Box:/ {print $2}' | tr -d '\n\r')
  start_pos=$(echo "$output" | awk -F': ' '/^Start Position:/ {print $2}' | tr -d '\n\r')
  start_zoom=$(echo "$output" | awk -F': ' '/^Start Zoom Level:/ {print $2}' | tr -d '\n\r')
  languages=$(echo "$output" | awk -F': ' '/^Preferred Languages:/ {print $2}' | tr -d '\n\r')
  comment=$(echo "$output" | awk -F': ' '/^Comment:/ {print $2}' | tr -d '\n\r')
  tile_size=$(echo "$output" | awk -F': ' '/^Tile Size:/ {print $2}' | tr -d '\n\r')
  is_debug=$(echo "$output" | awk -F': ' '/^Is Debug File:/ {print $2}' | tr -d '\n\r')

  poi_line=$(echo "$output" | grep "POI Tags")
  if [[ "$poi_line" == *"("* ]]; then
    poi_tags=$(echo "$poi_line" | sed -e 's/.*(//' -e 's/).*//' | tr -d '\n\r')
  else
    poi_tags=0
  fi

  way_line=$(echo "$output" | grep "Way Tags")
  if [[ "$way_line" == *"("* ]]; then
    way_tags=$(echo "$way_line" | sed -e 's/.*(//' -e 's/).*//' | tr -d '\n\r')
  else
    way_tags=0
  fi

  sub_files=$(echo "$output" | awk -F': ' '/^Number of Sub-Files:/ {print $2}' | tr -d '\n\r')

  # Append the data row to the CSV file, ensuring values are quoted
  echo "\"$filename\",\"$version\",\"$file_size\",\"$date\",\"$projection\",\"$created_by\",\"$bbox\",\"$start_pos\",\"$start_zoom\",\"$languages\",\"$comment\",\"$tile_size\",\"$is_debug\",\"$poi_tags\",\"$way_tags\",\"$sub_files\"" >> "$CSV_OUTPUT_FILE"
done

echo "Analysis complete. Results saved to $CSV_OUTPUT_FILE" 