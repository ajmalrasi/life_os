package main

import (
	"encoding/base64"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/99designs/keyring"
)

const (
	fileKeyPrefix = "_gogcli_key_v1_"
	serviceName   = "gogcli"
)

func main() {
	client := flag.String("client", "default", "gog OAuth client name")
	flag.Parse()

	dataDir, err := gogDataDir()
	if err != nil {
		fail(err)
	}
	password, ok := os.LookupEnv("GOG_KEYRING_PASSWORD")
	if !ok {
		fail(fmt.Errorf("GOG_KEYRING_PASSWORD is not set"))
	}

	ring, err := keyring.Open(keyring.Config{
		ServiceName:      serviceName,
		AllowedBackends:  []keyring.BackendType{keyring.FileBackend},
		FileDir:          filepath.Join(dataDir, "keyring"),
		FilePasswordFunc: keyring.FixedStringPrompt(password),
	})
	if err != nil {
		fail(fmt.Errorf("open gog keyring: %w", err))
	}

	rawKey := fmt.Sprintf("client/%s/client-secret", strings.TrimSpace(*client))
	encodedKey := fileKeyPrefix + base64.RawURLEncoding.EncodeToString([]byte(rawKey))
	item, err := ring.Get(encodedKey)
	if err != nil {
		fail(fmt.Errorf("read OAuth client secret: %w", err))
	}
	if len(item.Data) == 0 {
		fail(fmt.Errorf("OAuth client secret is empty"))
	}
	if _, err := os.Stdout.Write(item.Data); err != nil {
		fail(fmt.Errorf("return OAuth client secret: %w", err))
	}
}

func gogDataDir() (string, error) {
	if value := strings.TrimSpace(os.Getenv("GOG_DATA_DIR")); value != "" {
		return value, nil
	}
	if value := strings.TrimSpace(os.Getenv("GOG_HOME")); value != "" {
		return value, nil
	}
	if value := strings.TrimSpace(os.Getenv("XDG_DATA_HOME")); value != "" {
		return filepath.Join(value, "gogcli"), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("resolve home directory: %w", err)
	}
	return filepath.Join(home, ".local", "share", "gogcli"), nil
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(1)
}
