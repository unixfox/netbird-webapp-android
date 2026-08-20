package nbproxy

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/netbirdio/netbird/client/embed"
)

var (
	mu      sync.Mutex
	nb      *embed.Client
	srv     *http.Server
	ln      net.Listener
	started bool
)

// StartProxy boots the NetBird embedded client (userspace mode) and a
// localhost CONNECT proxy. Returns the local TCP port the proxy listens on.
// jwt: OIDC access token. mgmtURL: NetBird management URL. dir: app-private
// dir for config/state persistence (pass a path under the app's filesDir).
func StartProxy(jwt, mgmtURL, deviceName, dir string) (int, error) {
	return start(jwt, "", mgmtURL, deviceName, dir)
}

// StartProxyWithSetupKey is like StartProxy but authenticates with a
// NetBird setup key (bring-up / device-enrollment flow).
func StartProxyWithSetupKey(setupKey, mgmtURL, deviceName, dir string) (int, error) {
	return start("", setupKey, mgmtURL, deviceName, dir)
}

func start(jwt, setupKey, mgmtURL, deviceName, dir string) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	if started {
		return 0, fmt.Errorf("already started")
	}

	c, err := embed.New(embed.Options{
		DeviceName:    deviceName,
		JWTToken:      jwt,
		SetupKey:      setupKey,
		ManagementURL: mgmtURL,
		ConfigPath:    dir + "/netbird.config",
		StatePath:     dir + "/netbird.state",
		// userspace networking is the DEFAULT (NoUserspace left false) =>
		// no TUN, no VPNService, no root required.
		BlockInbound:   true, // this app never accepts inbound peer conns
		BlockLANAccess: true, // never a stepping stone into the phone's LAN
		LogLevel:       "info",
	})
	if err != nil {
		return 0, fmt.Errorf("embed.New: %w", err)
	}

	startCtx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := c.Start(startCtx); err != nil {
		return 0, fmt.Errorf("netbird start: %w", err)
	}
	nb = c

	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		_ = c.Stop(context.Background())
		nb = nil
		return 0, fmt.Errorf("listen: %w", err)
	}
	ln = l

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			handleConnect(w, r)
			return
		}
		handlePlainHTTP(w, r)
	})

	srv = &http.Server{Handler: handler}
	go func() { _ = srv.Serve(l) }()

	started = true
	return l.Addr().(*net.TCPAddr).Port, nil
}

// Stop tears down the proxy and the NetBird client.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if !started {
		return nil
	}
	if srv != nil {
		_ = srv.Close()
		srv = nil
	}
	if ln != nil {
		_ = ln.Close()
		ln = nil
	}
	var err error
	if nb != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		err = nb.Stop(ctx)
		nb = nil
	}
	started = false
	return err
}

// SetAllowedHosts restricts which CONNECT targets the proxy will tunnel to.
// Empty list means allow all (bring-up mode). Entries are suffix-matched
// against the hostname ("example.com" matches "grafana.example.com").
func SetAllowedHosts(hosts []string) {
	mu.Lock()
	defer mu.Unlock()
	allowed = hosts
}

var allowed []string

// Status returns a short human string for debugging from Kotlin.
func Status() string {
	mu.Lock()
	defer mu.Unlock()
	if nb == nil {
		return "stopped"
	}
	st, err := nb.Status()
	if err != nil {
		return "error: " + err.Error()
	}
	return fmt.Sprintf("peers=%d", len(st.Peers))
}

func hostAllowed(hostport string) bool {
	mu.Lock()
	defer mu.Unlock()
	if len(allowed) == 0 {
		return true
	}
	host := hostport
	if i := strings.LastIndex(hostport, ":"); i >= 0 {
		host = hostport[:i]
	}
	for _, a := range allowed {
		if host == a || strings.HasSuffix(host, "."+a) {
			return true
		}
	}
	return false
}

// FetchFavicon downloads a site's favicon through the overlay so it can be
// used as a home-screen shortcut icon. Returns raw image bytes (PNG/ICO).
func FetchFavicon(siteURL string) ([]byte, error) {
	mu.Lock()
	if nb == nil {
		mu.Unlock()
		return nil, errors.New("engine not started")
	}
	hc := nb.NewHTTPClient()
	hc.Timeout = 10 * time.Second
	mu.Unlock()

	u, err := url.Parse(siteURL)
	if err != nil {
		return nil, err
	}
	if u.Host == "" {
		return nil, errors.New("no host in url")
	}

	schemes := []string{u.Scheme}
	if u.Scheme == "https" {
		schemes = append(schemes, "http")
	} else if u.Scheme == "http" {
		schemes = append(schemes, "https")
	}

	var lastErr error
	for _, scheme := range schemes {
		if scheme != "http" && scheme != "https" {
			continue
		}
		origin := scheme + "://" + u.Host
		if b, err := tryOrigin(hc, origin); err == nil {
			return b, nil
		} else {
			lastErr = err
			fmt.Println("favicon miss (origin):", origin, err)
		}
	}
	if lastErr != nil {
		return nil, lastErr
	}
	return nil, fmt.Errorf("no favicon found for %s", siteURL)
}

func tryOrigin(hc *http.Client, origin string) ([]byte, error) {
	for _, c := range []string{
		origin + "/apple-touch-icon.png",
		origin + "/favicon.png",
		origin + "/favicon.ico",
	} {
		if b, err := fetchBytes(hc, c, false); err == nil {
			fmt.Println("favicon hit:", c, "bytes:", len(b))
			return b, nil
		} else {
			fmt.Println("favicon miss:", c, err)
		}
	}

	page, err := fetchBytes(hc, origin+"/", true)
	if err == nil {
		if href := extractIconHref(string(page), origin); href != "" {
			if b, err := fetchBytes(hc, href, false); err == nil {
				fmt.Println("favicon hit (page link):", href, "bytes:", len(b))
				return b, nil
			} else {
				fmt.Println("favicon miss (page link):", href, err)
			}
		} else {
			fmt.Println("favicon: no icon link in page")
		}
	} else {
		fmt.Println("favicon: page fetch failed:", err)
	}
	return nil, fmt.Errorf("no favicon at %s", origin)
}

func fetchBytes(hc *http.Client, url string, allowHTML bool) ([]byte, error) {
	resp, err := hc.Get(url)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("status %d", resp.StatusCode)
	}
	ct := resp.Header.Get("Content-Type")
	if !allowHTML {
		if ct != "" && !strings.HasPrefix(ct, "image/") &&
			!strings.Contains(ct, "octet-stream") && !strings.Contains(ct, "svg") {
			return nil, fmt.Errorf("content-type %s", ct)
		}
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if err != nil {
		return nil, err
	}
	if len(b) < 16 {
		return nil, errors.New("response too small")
	}
	return b, nil
}

var iconLinkRe = regexp.MustCompile(`(?i)<link[^>]+rel=["']?(?:shortcut\s+)?icon["']?[^>]*>`)
var iconHrefRe = regexp.MustCompile(`(?i)href=["']([^"']+)["']`)

func extractIconHref(page, origin string) string {
	var fallback string
	for _, m := range iconLinkRe.FindAllString(page, -1) {
		hm := iconHrefRe.FindStringSubmatch(m)
		if hm == nil {
			continue
		}
		href := hm[1]
		low := strings.ToLower(href)
		if strings.HasSuffix(low, ".svg") || strings.HasSuffix(low, ".svgz") {
			if fallback == "" {
				fallback = href
			}
			continue
		}
		if strings.HasPrefix(href, "//") {
			return "https:" + href
		}
		if strings.HasPrefix(href, "http") {
			return href
		}
		return origin + "/" + strings.TrimPrefix(href, "/")
	}
	if fallback == "" {
		return ""
	}
	if strings.HasPrefix(fallback, "//") {
		return "https:" + fallback
	}
	if strings.HasPrefix(fallback, "http") {
		return fallback
	}
	return origin + "/" + strings.TrimPrefix(fallback, "/")
}

// handleConnect tunnels an HTTP CONNECT (TLS) request to the overlay as a
// blind byte pipe. TLS stays end-to-end: WebView negotiates with the origin.
func handleConnect(w http.ResponseWriter, r *http.Request) {
	// r.Host is "app.internal.example.com:443" — the REAL name.
	// Hand it straight to NetBird; it resolves inside the overlay.
	fmt.Println("CONNECT", r.Host)
	if !hostAllowed(r.Host) {
		fmt.Println("CONNECT rejected (not in allowlist):", r.Host)
		http.Error(w, "host not allowed", http.StatusForbidden)
		return
	}
	dialCtx, dcancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer dcancel()
	upstream, err := nb.DialContext(dialCtx, "tcp", r.Host)
	if err != nil {
		fmt.Println("CONNECT dial error:", err)
		http.Error(w, "dial overlay: "+err.Error(), http.StatusBadGateway)
		return
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "no hijack", http.StatusInternalServerError)
		_ = upstream.Close()
		return
	}
	client, _, err := hj.Hijack()
	if err != nil {
		_ = upstream.Close()
		return
	}
	// Tell the browser the tunnel is open. From here we are a blind pipe;
	// WebView performs TLS end-to-end over this connection.
	_, _ = client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	go func() {
		_, _ = io.Copy(upstream, client)
		_ = upstream.Close()
	}()
	_, _ = io.Copy(client, upstream)
	_ = client.Close()
}

// handlePlainHTTP forwards a plain HTTP (absolute-form URI) request through
// the overlay. No TLS is involved; the bytes are forwarded as-is and the
// origin answers in cleartext inside the tunnel.
func handlePlainHTTP(w http.ResponseWriter, r *http.Request) {
	fmt.Println("PROXY", r.Method, r.URL.String())
	if !hostAllowed(r.Host) {
		fmt.Println("PROXY rejected (not in allowlist):", r.Host)
		http.Error(w, "host not allowed", http.StatusForbidden)
		return
	}
	if r.URL.Scheme != "" && r.URL.Scheme != "http" {
		http.Error(w, "unsupported scheme: "+r.URL.Scheme, http.StatusBadRequest)
		return
	}

	rp := &httputil.ReverseProxy{
		Director: func(req *http.Request) {
			req.URL.Scheme = "http"
			req.Host = r.Host
		},
		Transport: &http.Transport{
			DialContext:       nb.DialContext,
			ForceAttemptHTTP2: false,
		},
	}
	rp.ServeHTTP(w, r)
}