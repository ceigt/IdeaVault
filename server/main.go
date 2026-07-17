package main

import (
    "context"
    "crypto/rand"
    "crypto/sha256"
    "crypto/subtle"
    "encoding/base64"
    "encoding/hex"
    "encoding/json"
    "errors"
    "fmt"
    "log"
    "net/http"
    "os"
    "path/filepath"
    "regexp"
    "sort"
    "strings"
    "sync"
    "time"
)

const maxBodyBytes = 10 << 20

var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,31}$`)

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

type SyncResponse struct {
    Notes      []Envelope `json:"notes"`
    ServerTime int64      `json:"serverTime"`
}

type UserRecord struct {
    Username  string              `json:"username"`
    TokenHash string              `json:"tokenHash"`
    DataKey   string              `json:"dataKey"`
    KeyID     string              `json:"keyId,omitempty"`
    Notes     map[string]Envelope `json:"notes"`
}

type diskState struct {
    Users map[string]*UserRecord `json:"users"`
}

type legacyDiskState struct {
    KeyID string              `json:"keyId"`
    Notes map[string]Envelope `json:"notes"`
}

type Store struct {
    mu     sync.Mutex
    path   string
    users  map[string]*UserRecord
    legacy *legacyDiskState
}

func NewStore(path string) (*Store, error) {
    store := &Store{path: path, users: make(map[string]*UserRecord)}
    if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil { return nil, err }
    data, err := os.ReadFile(path)
    if errors.Is(err, os.ErrNotExist) { return store, nil }
    if err != nil { return nil, err }
    if len(data) == 0 { return store, nil }

    var state diskState
    if err := json.Unmarshal(data, &state); err == nil && state.Users != nil {
        store.users = state.Users
        for _, user := range store.users {
            if user.Notes == nil { user.Notes = make(map[string]Envelope) }
        }
        return store, nil
    }
    var legacy legacyDiskState
    if err := json.Unmarshal(data, &legacy); err != nil {
        return nil, fmt.Errorf("decode store: %w", err)
    }
    if legacy.Notes == nil { legacy.Notes = make(map[string]Envelope) }
    store.legacy = &legacy
    return store, nil
}

func (s *Store) BootstrapOwner(username, token, dataKey string) error {
    username = normalizeUsername(username)
    if err := validateUsername(username); err != nil { return err }
    if len(token) < 32 { return errors.New("SYNC_TOKEN must contain at least 32 characters") }
    if err := validateDataKey(dataKey); err != nil { return err }

    s.mu.Lock()
    defer s.mu.Unlock()
    record, exists := s.users[username]
    if !exists {
        record = &UserRecord{Username: username, Notes: make(map[string]Envelope)}
        if s.legacy != nil {
            record.KeyID = s.legacy.KeyID
            record.Notes = s.legacy.Notes
            s.legacy = nil
        }
        s.users[username] = record
    }
    if record.DataKey != "" && record.DataKey != dataKey && len(record.Notes) > 0 {
        return errors.New("DATA_KEY does not match existing owner data")
    }
    record.TokenHash = hashToken(token)
    record.DataKey = dataKey
    return s.saveLocked()
}

func (s *Store) CreateUser(username string) (string, error) {
    username = normalizeUsername(username)
    if err := validateUsername(username); err != nil { return "", err }
    tokenBytes := make([]byte, 32)
    keyBytes := make([]byte, 32)
    if _, err := rand.Read(tokenBytes); err != nil { return "", err }
    if _, err := rand.Read(keyBytes); err != nil { return "", err }
    token := hex.EncodeToString(tokenBytes)

    s.mu.Lock()
    defer s.mu.Unlock()
    if _, exists := s.users[username]; exists { return "", errors.New("username already exists") }
    s.users[username] = &UserRecord{
        Username: username,
        TokenHash: hashToken(token),
        DataKey: base64.StdEncoding.EncodeToString(keyBytes),
        Notes: make(map[string]Envelope),
    }
    if err := s.saveLocked(); err != nil { return "", err }
    return token, nil
}

func (s *Store) ListUsers() []map[string]any {
    s.mu.Lock()
    defer s.mu.Unlock()
    result := make([]map[string]any, 0, len(s.users))
    for _, user := range s.users {
        result = append(result, map[string]any{"username": user.Username, "noteCount": len(user.Notes)})
    }
    sort.Slice(result, func(i, j int) bool { return result[i]["username"].(string) < result[j]["username"].(string) })
    return result
}

func (s *Store) Authenticate(token string) (string, bool) {
    supplied := hashToken(token)
    s.mu.Lock()
    defer s.mu.Unlock()
    for username, user := range s.users {
        if len(supplied) == len(user.TokenHash) && subtle.ConstantTimeCompare([]byte(supplied), []byte(user.TokenHash)) == 1 {
            return username, true
        }
    }
    return "", false
}

func (s *Store) Config(username string) (string, bool) {
    s.mu.Lock()
    defer s.mu.Unlock()
    user, ok := s.users[username]
    if !ok { return "", false }
    return user.DataKey, true
}

func (s *Store) Merge(username, keyID string, incoming []Envelope) ([]Envelope, error) {
    s.mu.Lock()
    defer s.mu.Unlock()
    user, ok := s.users[username]
    if !ok { return nil, errors.New("unknown user") }
    decodedKeyID, err := base64.StdEncoding.DecodeString(keyID)
    if err != nil || len(decodedKeyID) != 32 { return nil, errors.New("invalid encryption key id") }
    changed := false
    if user.KeyID == "" { user.KeyID = keyID; changed = true }
    if user.KeyID != keyID { return nil, errors.New("encryption key mismatch") }
    for _, note := range incoming {
        if err := validateEnvelope(note); err != nil { return nil, err }
        current, exists := user.Notes[note.ID]
        if !exists || note.UpdatedAt > current.UpdatedAt ||
            (note.UpdatedAt == current.UpdatedAt && note.Ciphertext > current.Ciphertext) {
            user.Notes[note.ID] = note
            changed = true
        }
    }
    if changed {
        if err := s.saveLocked(); err != nil { return nil, err }
    }
    result := make([]Envelope, 0, len(user.Notes))
    for _, note := range user.Notes { result = append(result, note) }
    sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
    return result, nil
}

func (s *Store) saveLocked() error {
    data, err := json.Marshal(diskState{Users: s.users})
    if err != nil { return err }
    temp := s.path + ".tmp"
    if err := os.WriteFile(temp, data, 0600); err != nil { return err }
    return os.Rename(temp, s.path)
}

func validateEnvelope(note Envelope) error {
    if len(note.ID) == 0 || len(note.ID) > 128 || note.UpdatedAt <= 0 { return errors.New("invalid note metadata") }
    iv, err := base64.StdEncoding.DecodeString(note.IV)
    if err != nil || len(iv) != 12 { return errors.New("invalid note iv") }
    ciphertext, err := base64.StdEncoding.DecodeString(note.Ciphertext)
    if err != nil || len(ciphertext) < 16 || len(ciphertext) > 1<<20 { return errors.New("invalid note ciphertext") }
    return nil
}

func validateDataKey(value string) error {
    decoded, err := base64.StdEncoding.DecodeString(value)
    if err != nil || len(decoded) != 32 { return errors.New("DATA_KEY must be base64 containing exactly 32 random bytes") }
    return nil
}

func validateUsername(username string) error {
    if !usernamePattern.MatchString(username) { return errors.New("username must be 3-32 lowercase letters, numbers, _ or -") }
    return nil
}

func normalizeUsername(value string) string { return strings.ToLower(strings.TrimSpace(value)) }
func hashToken(token string) string { sum := sha256.Sum256([]byte(token)); return hex.EncodeToString(sum[:]) }

type contextKey string
const usernameContextKey contextKey = "username"

type Server struct {
    adminToken string
    store      *Store
}

func (s *Server) routes() http.Handler {
    mux := http.NewServeMux()
    mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, _ *http.Request) {
        writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
    })
    mux.Handle("GET /v1/config", s.userAuth(http.HandlerFunc(s.config)))
    mux.Handle("POST /v1/sync", s.userAuth(http.HandlerFunc(s.sync)))
    mux.Handle("GET /v1/admin/users", s.adminAuth(http.HandlerFunc(s.listUsers)))
    mux.Handle("POST /v1/admin/users", s.adminAuth(http.HandlerFunc(s.createUser)))
    return securityHeaders(mux)
}

func (s *Server) userAuth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
        username, ok := s.store.Authenticate(token)
        if !ok { writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"}); return }
        next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), usernameContextKey, username)))
    })
}

func (s *Server) adminAuth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        supplied := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
        if len(supplied) != len(s.adminToken) || subtle.ConstantTimeCompare([]byte(supplied), []byte(s.adminToken)) != 1 {
            writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"}); return
        }
        next.ServeHTTP(w, r)
    })
}

func requestUsername(r *http.Request) string { value, _ := r.Context().Value(usernameContextKey).(string); return value }

func (s *Server) config(w http.ResponseWriter, r *http.Request) {
    username := requestUsername(r)
    dataKey, ok := s.store.Config(username)
    if !ok { writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"}); return }
    writeJSON(w, http.StatusOK, map[string]any{"username": username, "dataKey": dataKey, "maxNoteBytes": 1 << 20})
}

func (s *Server) sync(w http.ResponseWriter, r *http.Request) {
    r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
    defer r.Body.Close()
    var request SyncRequest
    decoder := json.NewDecoder(r.Body)
    decoder.DisallowUnknownFields()
    if err := decoder.Decode(&request); err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request"}); return }
    if len(request.Notes) > 10000 { writeJSON(w, http.StatusBadRequest, map[string]string{"error": "too many notes"}); return }
    notes, err := s.store.Merge(requestUsername(r), request.KeyID, request.Notes)
    if err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()}); return }
    writeJSON(w, http.StatusOK, SyncResponse{Notes: notes, ServerTime: time.Now().UnixMilli()})
}

func (s *Server) createUser(w http.ResponseWriter, r *http.Request) {
    r.Body = http.MaxBytesReader(w, r.Body, 4096)
    defer r.Body.Close()
    var request struct { Username string `json:"username"` }
    if err := json.NewDecoder(r.Body).Decode(&request); err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": "invalid request"}); return }
    username := normalizeUsername(request.Username)
    token, err := s.store.CreateUser(username)
    if err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()}); return }
    writeJSON(w, http.StatusCreated, map[string]string{"username": username, "accessToken": token})
}

func (s *Server) listUsers(w http.ResponseWriter, _ *http.Request) { writeJSON(w, http.StatusOK, map[string]any{"users": s.store.ListUsers()}) }

func securityHeaders(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        w.Header().Set("Content-Type", "application/json; charset=utf-8")
        w.Header().Set("X-Content-Type-Options", "nosniff")
        w.Header().Set("Cache-Control", "no-store")
        next.ServeHTTP(w, r)
    })
}

func writeJSON(w http.ResponseWriter, status int, value any) { w.WriteHeader(status); _ = json.NewEncoder(w).Encode(value) }

func main() {
    adminToken := os.Getenv("ADMIN_TOKEN")
    if len(adminToken) < 32 { log.Fatal("ADMIN_TOKEN must contain at least 32 characters") }
    ownerToken := os.Getenv("SYNC_TOKEN")
    dataKey := os.Getenv("DATA_KEY")
    dataPath := os.Getenv("DATA_PATH")
    if dataPath == "" { dataPath = "/data/notes.json" }
    store, err := NewStore(dataPath)
    if err != nil { log.Fatal(err) }
    ownerUsername := os.Getenv("OWNER_USERNAME")
    if ownerUsername == "" { ownerUsername = "owner" }
    if err := store.BootstrapOwner(ownerUsername, ownerToken, dataKey); err != nil { log.Fatal(err) }
    port := os.Getenv("PORT")
    if port == "" { port = "8080" }
    server := &http.Server{
        Addr: ":" + port,
        Handler: (&Server{adminToken: adminToken, store: store}).routes(),
        ReadHeaderTimeout: 5 * time.Second,
        ReadTimeout: 15 * time.Second,
        WriteTimeout: 30 * time.Second,
        IdleTimeout: 60 * time.Second,
    }
    log.Printf("IdeaVault multi-user sync server listening on :%s", port)
    log.Fatal(server.ListenAndServe())
}