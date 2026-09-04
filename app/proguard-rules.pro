-keepattributes *Annotation*

# EVHardware persists these types with Gson. Their field names are the app-private history
# schema and must survive minification so a release can read history written by older builds.
-keep class com.evsuite.hardware.telemetry.EnergyTripSummary { *; }
-keep class com.evsuite.hardware.telemetry.StoredTrip { *; }
-keep class com.evsuite.hardware.telemetry.TripSample { *; }
-keep class com.evsuite.hardware.telemetry.TripHistoryFile { *; }
-keep class com.evsuite.hardware.telemetry.EnergyTripHistoryStore$HistoryEnvelope { *; }

# Evidence carried inside a stored trip. Never kept until now, so a release build renamed
# their fields and a stored trip came back with a firmware and no conversion version — which
# is exactly the field a model checks before trusting the trip. Both are the reason a model
# may refuse history, so both have to survive minification.
-keep class com.evsuite.hardware.BatteryPowerEvidence { *; }
-keep class com.evsuite.hardware.VehicleSpeedEvidence { *; }
-keep class com.evsuite.hardware.FirmwareInfo$Gen { *; }

# The fitted model is persisted the same way and read back by a later build.
-keep class com.evsuite.hardware.telemetry.model.EnergyModel { *; }
-keep class com.evsuite.hardware.telemetry.model.EnergyModelEnvelope { *; }

# Diagnostic artifacts leave the car and are read by a person. Minified they arrive as
# single-letter keys that have to be decoded by field order, which is how a wrong reading
# gets made.
-keep class com.evsuite.hardware.telemetry.EvidenceCapture { *; }
-keep class com.evsuite.hardware.telemetry.SignalEvidence { *; }
-keep class com.evsuite.hardware.telemetry.SignalKind { *; }
-keep class com.evsuite.chargepilot.NavGuidanceProbeArtifact { *; }
-keep class com.evsuite.chargepilot.TripHistoryArtifact { *; }
-keep class com.evsuite.chargepilot.SpeedScaleCheck$Result { *; }
-keep class com.evsuite.chargepilot.SpeedScaleCheck$Verdict { *; }
