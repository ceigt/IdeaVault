package main

import (
    "encoding/base64"
    "fmt"
    "net/http"
    "net/netip"
    "path/filepath"
    "testing"
)

func TestRegisterLoginIsolationAndPersistence(t *testing.T) {
    path := filepath.Join(t.TempDir(), "notes.json")
    store, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    ownerKey := base64.StdEncoding.EncodeToString(make([]byte, 32))
    if err := store.BootstrapOwner("owner", "0123456789abcdef0123456789abcdef", ownerKey); err != nil { t.Fatal(err) }

    token, err := store.Register("friend", "correct-horse-battery")
    if err != nil { t.Fatal(err) }
    if username, ok := store.Authenticate(token); !ok || username != "friend" { t.Fatal("registration token authentication failed") }
    if _, err := store.Login("friend", "wrong-password-value"); err == nil { t.Fatal("wrong password accepted") }
    loginToken, err := store.Login("FRIEND", "correct-horse-battery")
    if err != nil { t.Fatal(err) }
    if username, ok := store.Authenticate(loginToken); !ok || username != "friend" { t.Fatal("login token authentication failed") }

    iv := base64.StdEncoding.EncodeToString(make([]byte, 12))
    keyID := base64.StdEncoding.EncodeToString(make([]byte, 32))
    cipher := base64.StdEncoding.EncodeToString(make([]byte, 16))
    if _, err := store.Merge("owner", keyID, []Envelope{{ID: "owner-note", Ciphertext: cipher, IV: iv, UpdatedAt: 1}}); err != nil { t.Fatal(err) }
    friendNotes, err := store.Merge("friend", keyID, nil)
    if err != nil { t.Fatal(err) }
    if len(friendNotes) != 0 { t.Fatalf("friend saw owner notes: %#v", friendNotes) }

    reloaded, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    if username, ok := reloaded.Authenticate(loginToken); !ok || username != "friend" { t.Fatal("session did not persist") }
    if _, err := reloaded.Login("friend", "correct-horse-battery"); err != nil { t.Fatal("password did not persist") }
}

func TestPasswordChangeRevokesLegacyAndSessionTokens(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    key := base64.StdEncoding.EncodeToString(make([]byte, 32))
    legacyToken := "0123456789abcdef0123456789abcdef"
    if err := store.BootstrapOwner("owner", legacyToken, key); err != nil { t.Fatal(err) }
    if err := store.SetPassword("owner", "owner-password-2026"); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(legacyToken); ok { t.Fatal("legacy owner token survived password change") }
    loginToken, err := store.Login("owner", "owner-password-2026")
    if err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(loginToken); !ok { t.Fatal("new login token was rejected") }
    if err := store.SetPassword("owner", "new-owner-password-2026"); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(loginToken); ok { t.Fatal("session survived password change") }
    if err := store.BootstrapOwner("owner", legacyToken, key); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(legacyToken); ok { t.Fatal("bootstrap restored legacy token for password-enabled owner") }
}

func TestRejectsDuplicateInvalidAndWeakCredentials(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    if _, err := store.Register("A", "long-enough-password"); err == nil { t.Fatal("short username accepted") }
    if _, err := store.Register("valid_user", "short"); err == nil { t.Fatal("short password accepted") }
    if _, err := store.Register("valid_user", "long-enough-password"); err != nil { t.Fatal(err) }
    if _, err := store.Register("valid_user", "another-long-password"); err == nil { t.Fatal("duplicate username accepted") }
}
func TestNoteCountExcludesAndUpgradesDeletedTombstones(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    key := base64.StdEncoding.EncodeToString(make([]byte, 32))
    if err := store.BootstrapOwner("owner", "0123456789abcdef0123456789abcdef", key); err != nil { t.Fatal(err) }
    iv := base64.StdEncoding.EncodeToString(make([]byte, 12))
    keyID := base64.StdEncoding.EncodeToString(make([]byte, 32))
    cipher := base64.StdEncoding.EncodeToString(make([]byte, 16))
    notes := []Envelope{
        {ID: "active", Ciphertext: cipher, IV: iv, UpdatedAt: 1},
        {ID: "deleted", Ciphertext: cipher, IV: iv, UpdatedAt: 2},
    }
    if _, err := store.Merge("owner", keyID, notes); err != nil { t.Fatal(err) }
    upgraded := notes[1]
    upgraded.Deleted = true
    merged, err := store.Merge("owner", keyID, []Envelope{upgraded})
    if err != nil { t.Fatal(err) }
    if !merged[1].Deleted { t.Fatal("legacy tombstone metadata was not upgraded") }
    users := store.ListUsers()
    if got := users[0]["noteCount"]; got != 1 { t.Fatalf("noteCount = %v, want 1", got) }
}

func TestSessionExpiryAndLogout(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    token, err := store.Register("friend", "correct-horse-battery")
    if err != nil { t.Fatal(err) }
    if err := store.Logout(token); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(token); ok { t.Fatal("logged-out session remained valid") }

    token, err = store.Login("friend", "correct-horse-battery")
    if err != nil { t.Fatal(err) }
    store.mu.Lock()
    store.users["friend"].Sessions[0].ExpiresAt = store.now().Add(-1).UnixMilli()
    store.mu.Unlock()
    if _, ok := store.Authenticate(token); ok { t.Fatal("expired session remained valid") }
}

func TestRateLimitIgnoresSpoofedHeaderFromUntrustedPeer(t *testing.T) {
    server := &Server{}
    for attempt := 1; attempt <= 21; attempt++ {
        request := &http.Request{RemoteAddr: "203.0.113.10:1234", Header: make(http.Header)}
        request.Header.Set("X-Real-IP", fmt.Sprintf("198.51.100.%d", attempt))
        allowed := server.allowAuthAttempt(request)
        if attempt <= 20 && !allowed { t.Fatalf("attempt %d was blocked early", attempt) }
        if attempt == 21 && allowed { t.Fatal("spoofed X-Real-IP bypassed rate limit") }
    }
}

func TestRateLimitUsesHeaderOnlyFromTrustedProxy(t *testing.T) {
    server := &Server{trustedProxyCIDRs: []netip.Prefix{netip.MustParsePrefix("127.0.0.1/32")}}
    for attempt := 1; attempt <= 21; attempt++ {
        request := &http.Request{RemoteAddr: "127.0.0.1:1234", Header: make(http.Header)}
        request.Header.Set("X-Real-IP", fmt.Sprintf("198.51.100.%d", attempt))
        if !server.allowAuthAttempt(request) { t.Fatalf("distinct client %d behind trusted proxy was blocked", attempt) }
    }
}

func TestParseTrustedProxyCIDRs(t *testing.T) {
    prefixes, err := parseTrustedProxyCIDRs("127.0.0.1/32, 172.25.0.1/32")
    if err != nil { t.Fatal(err) }
    if len(prefixes) != 2 { t.Fatalf("got %d prefixes, want 2", len(prefixes)) }
    if _, err := parseTrustedProxyCIDRs("not-a-cidr"); err == nil { t.Fatal("invalid CIDR accepted") }
}
