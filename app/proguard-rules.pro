-keepattributes *Annotation*

# EVHardware persists these types with Gson. Their field names are the app-private history
# schema and must survive minification so a release can read history written by older builds.
-keep class com.evsuite.hardware.telemetry.EnergyTripSummary { *; }
-keep class com.evsuite.hardware.telemetry.StoredTrip { *; }
-keep class com.evsuite.hardware.telemetry.TripSample { *; }
-keep class com.evsuite.hardware.telemetry.TripHistoryFile { *; }
-keep class com.evsuite.hardware.telemetry.EnergyTripHistoryStore$HistoryEnvelope { *; }
