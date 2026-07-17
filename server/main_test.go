package main

import (
    "encoding/base64"
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

func TestOwnerCanAddPasswordWithoutLosingLegacyToken(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    key := base64.StdEncoding.EncodeToString(make([]byte, 32))
    legacyToken := "0123456789abcdef0123456789abcdef"
    if err := store.BootstrapOwner("owner", legacyToken, key); err != nil { t.Fatal(err) }
    if err := store.SetPassword("owner", "owner-password-2026"); err != nil { t.Fatal(err) }
    if _, ok := store.Authenticate(legacyToken); !ok { t.Fatal("legacy owner token stopped working") }
    if _, err := store.Login("owner", "owner-password-2026"); err != nil { t.Fatal(err) }
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
