// Package codes generates and normalises the six-character room codes used to
// address a Listen Together room.
//
// The alphabet deliberately omits O, I and L so a spoken or photographed code
// cannot be misread; Normalise additionally maps those characters back onto
// 0 and 1 when a user types them anyway.
//
// Ported from BitChord's Listen Together backend (GPL-3.0):
// https://github.com/kushagrasinghx/BitChord
package codes

import (
	"crypto/rand"
	"math/big"
	"strings"
	"unicode"
)

const (
	Alphabet   = "0123456789ABCDEFGHJKMNPQRSTUVWXYZ"
	CodeLength = 6
)

// NewCode generates a 6-character room code using cryptographically secure
// random choice.
func NewCode() string {
	var sb strings.Builder
	sb.Grow(CodeLength)
	alphaLen := big.NewInt(int64(len(Alphabet)))
	for i := 0; i < CodeLength; i++ {
		idx, err := rand.Int(rand.Reader, alphaLen)
		if err != nil {
			// Fallback: should practically never happen with crypto/rand.
			sb.WriteByte(Alphabet[0])
			continue
		}
		sb.WriteByte(Alphabet[idx.Int64()])
	}
	return sb.String()
}

// Normalise cleans a user-entered code:
//   - removes spaces, hyphens and other non-alphanumeric characters
//   - converts to uppercase
//   - maps commonly confused characters: I/L -> 1, O -> 0
func Normalise(code string) string {
	var sb strings.Builder
	for _, r := range strings.ToUpper(strings.TrimSpace(code)) {
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			continue
		}
		switch r {
		case 'I', 'L':
			sb.WriteByte('1')
		case 'O':
			sb.WriteByte('0')
		default:
			sb.WriteRune(r)
		}
	}
	return sb.String()
}

// IsValid checks whether the code is exactly CodeLength characters drawn from
// Alphabet.
func IsValid(code string) bool {
	if len(code) != CodeLength {
		return false
	}
	for i := 0; i < len(code); i++ {
		if !strings.ContainsRune(Alphabet, rune(code[i])) {
			return false
		}
	}
	return true
}
