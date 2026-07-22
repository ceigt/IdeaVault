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
    "net"
    "net/http"
    "net/netip"
    "os"
    "path/filepath"
    "regexp"
    "sort"
    "strconv"
    "strings"
    "sync"
    "time"

    "golang.org/x/crypto/argon2"
)

const (
    maxBodyBytes = 10 << 20
    maxSessions = 10
    defaultSessionTTL = 30 * 24 * time.Hour
    argonTime uint32 = 2
    argonMemory uint32 = 32 * 1024
    argonThreads uint8 = 1
    argonKeyLength uint32 = 32
)

var usernamePattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{2,31}$`)

type Envelope struct {
    ID string `json:"id"`
    Ciphertext string `json:"ciphertext"`
    IV string `json:"iv"`
    UpdatedAt int64 `json:"updatedAt"`
    Deleted bool `json:"deleted,omitempty"`
}

type SyncRequest struct {
    KeyID string `json:"keyId"`
    Notes []Envelope `json:"notes"`
}

type SyncResponse struct {
    Notes []Envelope `json:"notes"`
    ServerTime int64 `json:"serverTime"`
}

type UserRecord struct {
    Username string `json:"username"`
    TokenHash string `json:"tokenHash,omitempty"`
    SessionHashes []string `json:"sessionHashes,omitempty"`
    Sessions []SessionRecord `json:"sessions,omitempty"`
    PasswordHash string `json:"passwordHash,omitempty"`
    DataKey string `json:"dataKey"`
    KeyID string `json:"keyId,omitempty"`
    Notes map[string]Envelope `json:"notes"`
}

type SessionRecord struct {
    TokenHash string `json:"tokenHash"`
    ExpiresAt int64 `json:"expiresAt"`
}

type diskState struct { Users map[string]*UserRecord `json:"users"` }
type legacyDiskState struct { KeyID string `json:"keyId"`; Notes map[string]Envelope `json:"notes"` }

type Store struct {
    mu sync.Mutex
    path string
    users map[string]*UserRecord
    legacy *legacyDiskState
    sessionTTL time.Duration
    now func() time.Time
}

func NewStore(path string) (*Store, error) {
    store := &Store{path: path, users: make(map[string]*UserRecord), sessionTTL: defaultSessionTTL, now: time.Now}
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
            // Pre-v2.4 session hashes have no issue time, so they cannot be safely assigned a TTL.
            // Invalidate them during migration and require a fresh login.
            user.SessionHashes = nil
        }
        return store, nil
    }
    var legacy legacyDiskState
    if err := json.Unmarshal(data, &legacy); err != nil { return nil, fmt.Errorf("decode store: %w", err) }
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
    if record.DataKey != "" && record.DataKey != dataKey && len(record.Notes) > 0 { return errors.New("DATA_KEY does not match existing owner data") }
    if record.PasswordHash == "" { record.TokenHash = hashToken(token) } else { record.TokenHash = "" }
    record.DataKey = dataKey
    return s.saveLocked()
}

func (s *Store) Register(username, password string) (string, error) {
    username = normalizeUsername(username)
    if err := validateUsername(username); err != nil { return "", err }
    if err := validatePassword(password); err != nil { return "", err }
    passwordHash, err := hashPassword(password)
    if err != nil { return "", err }
    token, session, err := s.newSession()
    if err != nil { return "", err }
    keyBytes := make([]byte, 32)
    if _, err := rand.Read(keyBytes); err != nil { return "", err }
    s.mu.Lock()
    defer s.mu.Unlock()
    if _, exists := s.users[username]; exists { return "", errors.New("用户名已存在") }
    s.users[username] = &UserRecord{
        Username: username,
        Sessions: []SessionRecord{session},
        PasswordHash: passwordHash,
        DataKey: base64.StdEncoding.EncodeToString(keyBytes),
        Notes: make(map[string]Envelope),
    }
    if err := s.saveLocked(); err != nil { delete(s.users, username); return "", err }
    return token, nil
}

func (s *Store) Login(username, password string) (string, error) {
    username = normalizeUsername(username)
    if err := validateUsername(username); err != nil { return "", errors.New("用户名或密码错误") }
    s.mu.Lock()
    user, exists := s.users[username]
    encodedHash := ""
    if exists { encodedHash = user.PasswordHash }
    s.mu.Unlock()
    if encodedHash == "" {
        argon2.IDKey([]byte(password), make([]byte, 16), argonTime, argonMemory, argonThreads, argonKeyLength)
        return "", errors.New("用户名或密码错误")
    }
    if !verifyPassword(password, encodedHash) { return "", errors.New("用户名或密码错误") }
    token, session, err := s.newSession()
    if err != nil { return "", err }
    s.mu.Lock()
    defer s.mu.Unlock()
    user, exists = s.users[username]
    if !exists || user.PasswordHash != encodedHash { return "", errors.New("用户名或密码错误") }
    user.Sessions = pruneSessions(user.Sessions, s.now().UnixMilli())
    user.Sessions = append(user.Sessions, session)
    if len(user.Sessions) > maxSessions { user.Sessions = user.Sessions[len(user.Sessions)-maxSessions:] }
    if err := s.saveLocked(); err != nil { return "", err }
    return token, nil
}

func (s *Store) SetPassword(username, password string) error {
    if err := validatePassword(password); err != nil { return err }
    encodedHash, err := hashPassword(password)
    if err != nil { return err }
    s.mu.Lock()
    defer s.mu.Unlock()
    user, exists := s.users[username]
    if !exists { return errors.New("unknown user") }
    user.PasswordHash = encodedHash
    user.TokenHash = ""
    user.SessionHashes = nil
    user.Sessions = nil
    return s.saveLocked()
}

func (s *Store) ListUsers() []map[string]any {
    s.mu.Lock()
    defer s.mu.Unlock()
    result := make([]map[string]any, 0, len(s.users))
    for _, user := range s.users {
        result = append(result, map[string]any{
            "username": user.Username,
            "noteCount": activeNoteCount(user.Notes),
            "passwordEnabled": user.PasswordHash != "",
        })
    }
    sort.Slice(result, func(i, j int) bool { return result[i]["username"].(string) < result[j]["username"].(string) })
    return result
}

func activeNoteCount(notes map[string]Envelope) int {
    count := 0
    for _, note := range notes {
        if !note.Deleted { count++ }
    }
    return count
}

func (s *Store) Authenticate(token string) (string, bool) {
    supplied := hashToken(token)
    s.mu.Lock()
    now := s.now().UnixMilli()
    defer s.mu.Unlock()
    for username, user := range s.users {
        if user.PasswordHash == "" && secureHashEqual(supplied, user.TokenHash) { return username, true }
        for _, session := range user.Sessions {
            if session.ExpiresAt > now && secureHashEqual(supplied, session.TokenHash) { return username, true }
        }
    }
    return "", false
}

func (s *Store) Logout(token string) error {
    supplied := hashToken(token)
    s.mu.Lock()
    defer s.mu.Unlock()
    changed := false
    for _, user := range s.users {
        kept := user.Sessions[:0]
        for _, session := range user.Sessions {
            if secureHashEqual(supplied, session.TokenHash) { changed = true; continue }
            kept = append(kept, session)
        }
        user.Sessions = kept
    }
    if changed { return s.saveLocked() }
    return nil
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
        // Ciphertext ordering has no business meaning; it is only a stable tiebreaker so
        // concurrent writes with the same millisecond timestamp converge on every client.
        if !exists || note.UpdatedAt > current.UpdatedAt || (note.UpdatedAt == current.UpdatedAt && note.Ciphertext > current.Ciphertext) ||
            (note.UpdatedAt == current.UpdatedAt && note.Ciphertext == current.Ciphertext && !current.Deleted && note.Deleted) {
            user.Notes[note.ID] = note
            changed = true
        }
    }
    if changed { if err := s.saveLocked(); err != nil { return nil, err } }
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
    if !usernamePattern.MatchString(username) { return errors.New("用户名须为 3–32 位小写字母、数字、下划线或短横线") }
    return nil
}

func validatePassword(password string) error {
    if len(password) < 10 || len(password) > 128 { return errors.New("密码长度须为 10–128 个字符") }
    return nil
}

func hashPassword(password string) (string, error) {
    salt := make([]byte, 16)
    if _, err := rand.Read(salt); err != nil { return "", err }
    hash := argon2.IDKey([]byte(password), salt, argonTime, argonMemory, argonThreads, argonKeyLength)
    return fmt.Sprintf("v=1$%d$%d$%d$%s$%s", argonTime, argonMemory, argonThreads, base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(hash)), nil
}

func verifyPassword(password, encoded string) bool {
    parts := strings.Split(encoded, "$")
    if len(parts) != 6 || parts[0] != "v=1" { return false }
    timeValue, err1 := strconv.ParseUint(parts[1], 10, 32)
    memoryValue, err2 := strconv.ParseUint(parts[2], 10, 32)
    threadValue, err3 := strconv.ParseUint(parts[3], 10, 8)
    salt, err4 := base64.RawStdEncoding.DecodeString(parts[4])
    expected, err5 := base64.RawStdEncoding.DecodeString(parts[5])
    if err1 != nil || err2 != nil || err3 != nil || err4 != nil || err5 != nil || len(salt) != 16 || len(expected) != int(argonKeyLength) { return false }
    if timeValue < 1 || timeValue > 5 || memoryValue < 8*1024 || memoryValue > 64*1024 || threadValue < 1 || threadValue > 4 { return false }
    actual := argon2.IDKey([]byte(password), salt, uint32(timeValue), uint32(memoryValue), uint8(threadValue), uint32(len(expected)))
    return subtle.ConstantTimeCompare(actual, expected) == 1
}

func (s *Store) newSession() (string, SessionRecord, error) {
    tokenBytes := make([]byte, 32)
    if _, err := rand.Read(tokenBytes); err != nil { return "", SessionRecord{}, err }
    token := hex.EncodeToString(tokenBytes)
    return token, SessionRecord{TokenHash: hashToken(token), ExpiresAt: s.now().Add(s.sessionTTL).UnixMilli()}, nil
}

func pruneSessions(sessions []SessionRecord, now int64) []SessionRecord {
    kept := sessions[:0]
    for _, session := range sessions { if session.ExpiresAt > now { kept = append(kept, session) } }
    return kept
}

func normalizeUsername(value string) string { return strings.ToLower(strings.TrimSpace(value)) }
func hashToken(token string) string { sum := sha256.Sum256([]byte(token)); return hex.EncodeToString(sum[:]) }
func secureHashEqual(left, right string) bool { return len(left) == len(right) && subtle.ConstantTimeCompare([]byte(left), []byte(right)) == 1 }

type contextKey string
const usernameContextKey contextKey = "username"

const tokenContextKey contextKey = "token"
type authAttempt struct { windowStart time.Time; count int }

type Server struct {
    adminToken string
    registrationEnabled bool
    store *Store
    attemptMu sync.Mutex
    trustedProxyCIDRs []netip.Prefix
    attempts map[string]authAttempt
}

func (s *Server) routes() http.Handler {
    if s.attempts == nil { s.attempts = make(map[string]authAttempt) }
    mux := http.NewServeMux()
    mux.HandleFunc("GET /v1/health", func(w http.ResponseWriter, _ *http.Request) { writeJSON(w, http.StatusOK, map[string]string{"status": "ok"}) })
    mux.HandleFunc("POST /v1/auth/register", s.register)
    mux.HandleFunc("POST /v1/auth/login", s.login)
    mux.Handle("POST /v1/auth/password", s.userAuth(http.HandlerFunc(s.setPassword)))
    mux.Handle("GET /v1/config", s.userAuth(http.HandlerFunc(s.config)))
    mux.Handle("POST /v1/auth/logout", s.userAuth(http.HandlerFunc(s.logout)))
    mux.Handle("POST /v1/sync", s.userAuth(http.HandlerFunc(s.sync)))
    mux.Handle("GET /v1/admin/users", s.adminAuth(http.HandlerFunc(s.listUsers)))
    return securityHeaders(mux)
}

func (s *Server) allowAuthAttempt(r *http.Request) bool {
    host := s.clientAddress(r)
    now := time.Now()
    s.attemptMu.Lock()
    if s.attempts == nil { s.attempts = make(map[string]authAttempt) }
    defer s.attemptMu.Unlock()
    if len(s.attempts) > 10000 {
        for address, existing := range s.attempts {
            if now.Sub(existing.windowStart) >= time.Minute { delete(s.attempts, address) }
        }
    }
    attempt := s.attempts[host]
    if attempt.windowStart.IsZero() || now.Sub(attempt.windowStart) >= time.Minute { attempt = authAttempt{windowStart: now} }
    attempt.count++
    s.attempts[host] = attempt
    return attempt.count <= 20
}

func (s *Server) clientAddress(r *http.Request) string {
    host, _, err := net.SplitHostPort(r.RemoteAddr)
    if err != nil { host = strings.Trim(r.RemoteAddr, "[]") }
    peer, err := netip.ParseAddr(host)
    if err != nil { return host }
    peer = peer.Unmap()
    for _, trusted := range s.trustedProxyCIDRs {
        if trusted.Contains(peer) {
            forwarded, err := netip.ParseAddr(strings.TrimSpace(r.Header.Get("X-Real-IP")))
            if err == nil { return forwarded.Unmap().String() }
            break
        }
    }
    return peer.String()
}

func (s *Server) register(w http.ResponseWriter, r *http.Request) {
    if !s.registrationEnabled { writeJSON(w, http.StatusForbidden, map[string]string{"error": "服务器已关闭新用户注册"}); return }
    if !s.allowAuthAttempt(r) { writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "尝试次数过多，请稍后再试"}); return }
    username, password, ok := decodeCredentials(w, r)
    if !ok { return }
    token, err := s.store.Register(username, password)
    if err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()}); return }
    writeJSON(w, http.StatusCreated, map[string]string{"username": normalizeUsername(username), "accessToken": token})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
    if !s.allowAuthAttempt(r) { writeJSON(w, http.StatusTooManyRequests, map[string]string{"error": "尝试次数过多，请稍后再试"}); return }
    username, password, ok := decodeCredentials(w, r)
    if !ok { return }
    token, err := s.store.Login(username, password)
    if err != nil { writeJSON(w, http.StatusUnauthorized, map[string]string{"error": err.Error()}); return }
    writeJSON(w, http.StatusOK, map[string]string{"username": normalizeUsername(username), "accessToken": token})
}

func decodeCredentials(w http.ResponseWriter, r *http.Request) (string, string, bool) {
    r.Body = http.MaxBytesReader(w, r.Body, 4096)
    defer r.Body.Close()
    var request struct { Username string `json:"username"`; Password string `json:"password"` }
    decoder := json.NewDecoder(r.Body)
    decoder.DisallowUnknownFields()
    if err := decoder.Decode(&request); err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求格式错误"}); return "", "", false }
    return request.Username, request.Password, true
}

func (s *Server) userAuth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
        username, ok := s.store.Authenticate(token)
        if !ok { writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "登录已失效，请重新登录"}); return }
        ctx := context.WithValue(r.Context(), usernameContextKey, username)
        ctx = context.WithValue(ctx, tokenContextKey, token)
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}

func (s *Server) adminAuth(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        supplied := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
        if !secureHashEqual(supplied, s.adminToken) { writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "unauthorized"}); return }
        next.ServeHTTP(w, r)
    })
}

func requestUsername(r *http.Request) string { value, _ := r.Context().Value(usernameContextKey).(string); return value }

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
    token, _ := r.Context().Value(tokenContextKey).(string)
    if err := s.store.Logout(token); err != nil { writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "logout failed"}); return }
    writeJSON(w, http.StatusOK, map[string]string{"status": "logged-out"})
}

func (s *Server) setPassword(w http.ResponseWriter, r *http.Request) {
    r.Body = http.MaxBytesReader(w, r.Body, 4096)
    defer r.Body.Close()
    var request struct { Password string `json:"password"` }
    if err := json.NewDecoder(r.Body).Decode(&request); err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": "请求格式错误"}); return }
    if err := s.store.SetPassword(requestUsername(r), request.Password); err != nil { writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()}); return }
    writeJSON(w, http.StatusOK, map[string]string{"status": "password-set"})
}

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
    dataPath := os.Getenv("DATA_PATH")
    if dataPath == "" { dataPath = "/data/notes.json" }
    store, err := NewStore(dataPath)
    if err != nil { log.Fatal(err) }
    sessionTTLHours := 720
    if value := os.Getenv("SESSION_TTL_HOURS"); value != "" {
        parsed, err := strconv.Atoi(value)
        if err != nil || parsed < 1 || parsed > 8760 { log.Fatal("SESSION_TTL_HOURS must be an integer from 1 to 8760") }
        sessionTTLHours = parsed
    }
    store.sessionTTL = time.Duration(sessionTTLHours) * time.Hour
    trustedProxyCIDRs, err := parseTrustedProxyCIDRs(os.Getenv("TRUSTED_PROXY_CIDRS"))
    if err != nil { log.Fatal(err) }
    ownerUsername := os.Getenv("OWNER_USERNAME")
    if ownerUsername == "" { ownerUsername = "owner" }
    if err := store.BootstrapOwner(ownerUsername, os.Getenv("SYNC_TOKEN"), os.Getenv("DATA_KEY")); err != nil { log.Fatal(err) }
    registrationEnabled := true
    if value := os.Getenv("ALLOW_REGISTRATION"); value != "" {
        parsed, err := strconv.ParseBool(value)
        if err != nil { log.Fatal("ALLOW_REGISTRATION must be true or false") }
        registrationEnabled = parsed
    }
    port := os.Getenv("PORT")
    if port == "" { port = "8080" }
    server := &http.Server{
        Addr: ":" + port,
        Handler: (&Server{adminToken: adminToken, registrationEnabled: registrationEnabled, store: store, trustedProxyCIDRs: trustedProxyCIDRs}).routes(),
        ReadHeaderTimeout: 5 * time.Second,
        ReadTimeout: 20 * time.Second,
        WriteTimeout: 30 * time.Second,
        IdleTimeout: 60 * time.Second,
    }
    log.Printf("IdeaVault sync server listening on :%s (registration=%t, session_ttl_hours=%d, trusted_proxies=%d)", port, registrationEnabled, sessionTTLHours, len(trustedProxyCIDRs))
    log.Fatal(server.ListenAndServe())
}
func parseTrustedProxyCIDRs(value string) ([]netip.Prefix, error) {
    if strings.TrimSpace(value) == "" { return nil, nil }
    parts := strings.Split(value, ",")
    result := make([]netip.Prefix, 0, len(parts))
    for _, part := range parts {
        prefix, err := netip.ParsePrefix(strings.TrimSpace(part))
        if err != nil { return nil, fmt.Errorf("invalid TRUSTED_PROXY_CIDRS entry %q", strings.TrimSpace(part)) }
        result = append(result, prefix)
    }
    return result, nil
}
