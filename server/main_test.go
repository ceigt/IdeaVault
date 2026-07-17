package main

import (
    "encoding/base64"
    "path/filepath"
    "testing"
)

func TestMergeUsesNewestEnvelopeAndPersists(t *testing.T) {
    path := filepath.Join(t.TempDir(), "notes.json")
    store, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    iv := base64.StdEncoding.EncodeToString(make([]byte, 12))
    keyID := base64.StdEncoding.EncodeToString(make([]byte, 32))
    oldCipher := base64.StdEncoding.EncodeToString(make([]byte, 16))
    newBytes := make([]byte, 16)
    newBytes[0] = 1
    newCipher := base64.StdEncoding.EncodeToString(newBytes)

    _, err = store.Merge(keyID, []Envelope{{ID: "one", Ciphertext: oldCipher, IV: iv, UpdatedAt: 1}})
    if err != nil { t.Fatal(err) }
    result, err := store.Merge(keyID, []Envelope{{ID: "one", Ciphertext: newCipher, IV: iv, UpdatedAt: 2}})
    if err != nil { t.Fatal(err) }
    if len(result) != 1 || result[0].Ciphertext != newCipher { t.Fatalf("unexpected result: %#v", result) }

    reloaded, err := NewStore(path)
    if err != nil { t.Fatal(err) }
    persisted, err := reloaded.Merge(keyID, nil)
    if err != nil { t.Fatal(err) }
    if len(persisted) != 1 || persisted[0].UpdatedAt != 2 { t.Fatalf("not persisted: %#v", persisted) }
}