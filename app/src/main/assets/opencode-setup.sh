[ -t 0 ] || return 0
[ -n "$OC_AUTO" ] && return 0
export OC_AUTO=1
if [ ! -f "$HOME/.oc-setup-done" ]; then
  echo "== Cai dat OpenCode lan dau (can mang) =="
  until pkg update -y && pkg install -y glibc-repo && pkg install -y glibc-runner proot curl unzip && curl -fsSL https://opencode.ai/v2/install | bash; do
    echo "Loi, thu lai sau 5 giay..."; sleep 5
  done
  [ -f "$HOME/.bashrc" ] && sed -i '/alias opencode/d;/\.opencode\/bin/d' "$HOME/.bashrc"
  cat > "$PREFIX/bin/opencode" <<'EOT'
#!/data/data/com.termux/files/usr/bin/sh
exec proot -b "$PREFIX/glibc/lib/ld-linux-aarch64.so.1:/lib/ld-linux-aarch64.so.1" env -u LD_PRELOAD "$HOME/.opencode/bin/opencode" "$@"
EOT
  chmod +x "$PREFIX/bin/opencode"
  touch "$HOME/.oc-setup-done"
fi
termux-wake-lock
opencode
