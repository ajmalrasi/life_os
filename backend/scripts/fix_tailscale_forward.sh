#!/bin/sh
# Works around a Jetson-specific Tailscale bug: tailscaled's generated
# nftables chain (ts-forward) is supposed to accept forwarded traffic
# arriving on tailscale0, but it does so via a connmark-based rule that
# silently never matches on this device's custom NVIDIA L4T kernel (missing
# xt_connmark/CONNMARK kernel module — visible as a health-check warning in
# `tailscale status`: "Extension connmark revision 0 not supported, missing
# kernel module"). Net effect: tailnet peers (e.g. the phone away from home)
# can ping the Jetson but TCP to the backend (port 8000, forwarded into the
# Docker bridge) times out, because the marking rule that's supposed to let
# it through never fires.
#
# Fix: insert one unconditional accept for tailscale0-sourced forwarded
# traffic directly in the top-level FORWARD chain, which sits alongside (not
# inside) tailscaled's self-managed ts-forward chain, so tailscaled
# regenerating its own rules doesn't remove this one. Idempotent — safe to
# run on every boot.
#
# See AGENTS.md §2 (Stage 2c) for the diagnosis this came from.

set -eu

if ! nft list chain ip filter FORWARD 2>/dev/null | grep -q 'iifname "tailscale0" accept'; then
    nft insert rule ip filter FORWARD iifname "tailscale0" accept
fi
