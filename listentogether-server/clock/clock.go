// Package clock provides the one server clock, monotonically advanced from a
// wall-clock sample taken at startup so an NTP step on the host cannot rewrite
// a playback anchor under a room full of phones at once.
//
// Ported from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package clock

import "time"

var (
	epochWallMs    = time.Now().UnixMilli()
	epochMonotonic = time.Now()
)

// NowMs returns the server time in milliseconds since the Unix epoch,
// advancing monotonically from startup.
func NowMs() int64 {
	elapsed := time.Since(epochMonotonic).Milliseconds()
	return epochWallMs + elapsed
}
