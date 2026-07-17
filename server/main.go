package main

import (
    "crypto/subtle"
    "encoding/base64"
    "encoding/json"
    "errors"
    "fmt"
    "log"
    "net/http"
    "os"
    "path/filepath"
    "sort"
    "strings"
    "sync"
    "time"
)

const maxBodyBytes = 10 << 20

type Envelope struct {
    ID         string `json:"id"`
    Ciphertext string `json:"ciphertext"`
    IV         string `json:"iv"`
    UpdatedAt  int64  `json:"updatedAt"`
}

type SyncRequest struct {
    KeyID string     `json:"keyId"`
    Notes []Envelope `json:"notes"`
}

type diskState struct {
    KeyID string              `json:"keyId"`
    Notes map[string]Envelope `json:"notes"`
}

type SyncResponse struct {
    Notes      []Envelope `json:"notes"`
    ServerTime int64      `json:"serverTime"`
}

type Store struct {
    mu    sync.Mutex
    path  string
    keyID string
    notes map[string]Envelope
}

func NewStore(path string) (*Store, error) {
    store := &Store{path: path, notes: make(map[string]Envelope)}
    if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
        return nil, err
    }
    data, err := os.ReadFile(path)
    if errors.Is(err, os.ErrNotExist) {
        return store, nil
    }
    if err != nil {
        return nil, err
    }
    if len(data) > 0 {
        var state diskState
        if err := json.Unmarshal(data, &state); err != nil {
            return nil, fmt.Errorf("decode store: %w", err)
        }
        store.keyID = state.KeyID
        if state.Notes != nil { store.notes = state.Notes }
    }
    return store, nil
}

func (s *Store) Merge(keyID string, incoming []Envelope) ([]Envelope, error) {
    s.mu.Lock()
    defer s.mu.Unlock()

    decodedKeyID, err := base64.StdEncoding.DecodeString(keyID)
    if err != nil || len(decodedKeyID) != 32 { return nil, errors.New("invalid encryption key id") }
    changed := false
    if s.keyID == "" { s.keyID = keyID; changed = true }
    if s.keyID != keyID { return nil, errors.New("encryption key mismatch") }
    for _, note := range incoming {
        if err := validateEnvelope(note); err != nil {
            return nil, err
        }
        current, exists := s.notes[note.ID]
        if !exists || note.UpdatedAt > current.UpdatedAt ||
            (note.UpdatedAt == current.UpdatedAt && note.Ciphertext > current.Ciphertext) {
            s.notes[note.ID] = note
            changed = true
        }
    }
    if changed {
        if err := s.saveLocked(); err != nil {
            return nil, err
        }
    }

    result := make([]Envelope, 0, len(s.notes))
    for _, note := range s.notes {
        result = append(result, note)
    }
    sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
    return result, nil
}

func (s *Store) saveLocked() error {
    data, err := json.Marshal(diskState{KeyID: s.keyID, Notes: s.notes})
    if err != nil {
        return err
    }
    temp := s.path + ".tmp"
    if err := os.WriteFile(temp, data, 0600); err != nil {
        return err
    }
    return os.Rename(temp, s.path)
}

func validateEnvelope(note Envelope) error {
    if len(note.ID) == 0 || len(note.ID) > 128 || note.UpdatedAt <= 0 {
        return errors.New("invalid note metadata")
    }
    iv, err := base64.StdEncoding.DecodeString(note.IV)
    if err != nil || len(iv) != 12 {
        return errors.New("invalid note iv")
    }
    ciphertext, err := base64.StdEncoding.DecodeString(note.Ciphertext)
    if err != nil || len(ciphertext) < 16 || len(ciphertext) > 1<<20 {
        return errors.New("invalid note ciphertext")
    }
    return nil
}

type Server struct {
    token   string
    kdfSalt string
    store   *Store
}

func (s *Server) routes() http.Handler {
    mux := http.NewServeMux()
    mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, _ *http.Request) {
        writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
    })
    mux.Handle("GET /v1/config", s.auth(http.HandlerFunc(s.config)))
    mux.Handle("POST /v1/sync", s.auth(http.HandlerFunc(s.sync)))
    return securityHeaders(mux)
}

func (s *Server) auth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        supplied := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
        if len(supplied) != len(s.token) || subtle.ConstantTimeCompare([]byte(supplied), []byte(s.token)) != 1 {
            writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
            return
        }
        next.ServeHTTP(w, r)
    })
}

func (s *Server) config(w http.ResponseWriter, _ *http.Request) {
    writeJSON(w, http.StatusOK, map[string]any{
        "kdfSalt":       s.kdfSalt,
        "kdfIterations": 310000,
        "maxNoteBytes":  1 << 20,
    })
}

func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
    r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
    defer r.Body.Close()
    var request SyncRequest
    decoder := json.NewDecoder(r.Body)
    decoder.DisallowUnknownFields()
    if err := decoder.Decode(&request); err != nil {
        status := http.StatusBadRequest
        if errors.As(err, new(*http.MaxBytesError)) {
            status = http.StatusRequestEntityTooLarge
        }
        writeJSON(w, status, map[string]string{"error": "invalid request"})
        return
    }
    if len(request.Notes) > 10000 {
        writeJSON(w, http.StatusBadRequest, map[string]string{"error": "too many notes"})
        return
    }
    notes, err := s.store.Merge(request.KeyID, request.Notes)
    if err != nil {
        writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
        return
    }
    writeJSON(w, http.StatusOK, SyncResponse{Notes: notes, ServerTime: time.Now().UnixMilli()})
}

func securityHeaders(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("X-Content-Type-Options", "nosniff")
        w.Header().Set("Cache-Control", "no-store")
        next.ServeHTTP(w, r)
    })
}

func writeJSON(w http.ResponseWriter, status int, value any) {
    w.WriteHeader(status)
    _ = json.NewEncoder(w).Encode(value)
}

func main() {
    token := os.Getenv("SYNC_TOKEN")
    if len(token) < 32 {
        log.Fatal("SYNC_TOKEN must contain at least 32 characters")
    }
    salt := os.Getenv("KDF_SALT")
    decodedSalt, err := base64.StdEncoding.DecodeString(salt)
    if err != nil || len(decodedSalt) < 16 {
        log.Fatal("KDF_SALT must be base64 containing at least 16 random bytes")
    }
    dataPath := os.Getenv("DATA_PATH")
    if dataPath == "" {
        dataPath = "/data/notes.json"
    }
    store, err := NewStore(dataPath)
    if err != nil {
        log.Fatal(err)
    }
    port := os.Getenv("PORT")
    if port == "" {
        port = "8080"
    }
    server := &http.Server{
        Addr:              ":" + port,
        Handler:           (&Server{token: token, kdfSalt: salt, store: store}).routes(),
        ReadHeaderTimeout: 5 * time.Second,
        ReadTimeout:       15 * time.Second,
        WriteTimeout:      30 * time.Second,
        IdleTimeout:       60 * time.Second,
    }
    log.Printf("IdeaVault sync server listening on :%s", port)
    log.Fatal(server.ListenAndServe())
}

var _ io.Reader