// Streaming recognizer was REMOVED from the shipped app — the team chose the
// original batch version (LocalRecognitionService).
//
// The full streaming code is backed up at:
//     dumb dom/StreamingRecognitionService-BACKUP.kt
// To bring it back: copy that file here, re-add its <service> block to
// AndroidManifest.xml, rebuild, then point the recognizer setting at
//     com.offlineinc.voicetotext/.StreamingRecognitionService
package com.offlineinc.voicetotext
