package main

import (
    "encoding/base64"
    "path/filepath"
    "testing"
)

func TestUsersAreIsolatedAndPersisted(t *testing.T) {
    path := filepath.Join(t.TempDir(), "notes.json")
    store, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    key := base64.StdEncoding.EncodeToString(make([]byte, 32))
    if err := store.BootstrapOwner("owner", "0123456789abcdef0123456789abcdef", key); err != nil { t.Fatal(err) }
    friendToken, err := store.CreateUser("friend")
    if err != nil { t.Fatal(err) }
    if username, ok := store.Authenticate(friendToken); !ok || username != "friend" { t.Fatal("friend authentication failed") }

    iv := base64.StdEncoding.EncodeToString(make([]byte, 12))
    keyID := base64.StdEncoding.EncodeToString(make([]byte, 32))
    cipher := base64.StdEncoding.EncodeToString(make([]byte, 16))
    _, err = store.Merge("owner", keyID, []Envelope{{ID: "owner-note", Ciphertext: cipher, IV: iv, UpdatedAt: 1}})
    if err != nil { t.Fatal(err) }
    friendNotes, err := store.Merge("friend", keyID, nil)
    if err != nil { t.Fatal(err) }
    if len(friendNotes) != 0 { t.Fatalf("friend saw owner notes: %#v", friendNotes) }

    reloaded, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    ownerNotes, err := reloaded.Merge("owner", keyID, nil)
    if err != nil || len(ownerNotes) != 1 { t.Fatalf("owner notes not persisted: %#v %v", ownerNotes, err) }
}

func TestRejectsDuplicateAndInvalidUsername(t *testing.T) {
    store, err := NewStore(filepath.Join(t.TempDir(), "notes.json"))
    if err != nil { t.Fatal(err) }
    if _, err := store.CreateUser("A"); err == nil { t.Fatal("short username accepted") }
    if _, err := store.CreateUser("valid_user"); err != nil { t.Fatal(err) }
    if _, err := store.CreateUser("valid_user"); err == nil { t.Fatal("duplicate username accepted") }
}