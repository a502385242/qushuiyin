#!/usr/bin/env python3
"""
manifest 配置签名工具（keygen / sign / verify）

为什么签名：配置从 GitHub Raw / jsDelivr 拉取，任何人 fork 或投毒 CDN 都可能塞一份
假规则，把全体用户的解析导向钓鱼地址。App 内置公钥验签，签不过就不采用。

签名对象是 manifest.json 的**原始字节**，不是重新序列化的 JSON。
这样做的好处：签名端（本脚本）和验签端（Kotlin）不需要实现一致的 JSON 规范化规则，
少一类「本地验得过、线上验不过」的诡异问题。

依赖：openssl（Git for Windows 自带，Linux/macOS 一般也有）

用法：
    python tools/manifest_tool.py keygen
    python tools/manifest_tool.py sign   config/manifest.json
    python tools/manifest_tool.py verify config/manifest.json
"""
import argparse
import base64
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KEYS = ROOT / "keys"
PRIVATE_PEM = KEYS / "private.pem"
PUBLIC_PEM = KEYS / "public.pem"
PUBLIC_B64 = KEYS / "public.b64"


def need_openssl() -> str:
    exe = shutil.which("openssl")
    if not exe:
        sys.exit("找不到 openssl，请先安装，或使用 Git Bash 打开后重试")
    return exe


def run(cmd: list[str]) -> None:
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"命令失败: {' '.join(cmd)}\n{r.stderr}")


def cmd_keygen(_args) -> None:
    openssl = need_openssl()
    KEYS.mkdir(exist_ok=True)

    if PRIVATE_PEM.exists():
        sys.exit(f"{PRIVATE_PEM} 已存在。私钥丢失意味着老版本 App 再也无法信任新配置，"
                 f"确实要重建请先手动移走它。")

    # P-256（prime256v1）：Android API 26 起原生支持，不需要 BouncyCastle
    run([openssl, "ecparam", "-genkey", "-name", "prime256v1", "-noout", "-out", str(PRIVATE_PEM)])
    run([openssl, "pkey", "-in", str(PRIVATE_PEM), "-pubout", "-out", str(PUBLIC_PEM)])
    run([openssl, "pkey", "-in", str(PRIVATE_PEM), "-pubout", "-outform", "DER",
         "-out", str(KEYS / "public.der")])

    pub_der = (KEYS / "public.der").read_bytes()
    pub_b64 = base64.b64encode(pub_der).decode()
    PUBLIC_B64.write_text(pub_b64 + "\n", encoding="utf-8")

    print(f"私钥（务必只留本地、绝不提交）：{PRIVATE_PEM}")
    print(f"公钥 Base64：\n{pub_b64}")
    print("\n把下面这一行写进 local.properties，App 就会用它验签：")
    print(f"repair.pubkey={pub_b64}")
    print("\n注意：私钥一旦丢失，已发布的 App 将无法信任任何新配置，只能靠发新版换公钥。")


def _sig_path(manifest: Path) -> Path:
    return manifest.with_suffix(manifest.suffix + ".sig")


def cmd_sign(args) -> None:
    openssl = need_openssl()
    manifest = Path(args.manifest).resolve()
    if not manifest.exists():
        sys.exit(f"找不到 {manifest}")
    if not PRIVATE_PEM.exists():
        sys.exit("缺少私钥，请先执行 keygen")

    tmp = manifest.with_suffix(".sig.der")
    run([openssl, "dgst", "-sha256", "-sign", str(PRIVATE_PEM),
         "-out", str(tmp), str(manifest)])

    sig_b64 = base64.b64encode(tmp.read_bytes()).decode()
    tmp.unlink()
    out = _sig_path(manifest)
    out.write_text(sig_b64 + "\n", encoding="utf-8")
    print(f"已签名：{manifest.name} -> {out.name}")
    print(f"签名字节数：{len(sig_b64)}")


def cmd_verify(args) -> None:
    openssl = need_openssl()
    manifest = Path(args.manifest).resolve()
    sig_file = _sig_path(manifest)
    if not sig_file.exists():
        sys.exit(f"找不到签名文件 {sig_file}")

    der = manifest.with_suffix(".verify.der")
    der.write_bytes(base64.b64decode(sig_file.read_text(encoding="utf-8").strip()))
    r = subprocess.run(
        [openssl, "dgst", "-sha256", "-verify", str(PUBLIC_PEM), "-signature", str(der), str(manifest)],
        capture_output=True, text=True
    )
    der.unlink()
    print(r.stdout.strip() or r.stderr.strip())
    if r.returncode != 0:
        sys.exit("验签失败")
    print("验签通过 ✓")


def main() -> None:
    p = argparse.ArgumentParser(description="manifest 签名工具")
    sub = p.add_subparsers(required=True)

    sub.add_parser("keygen", help="生成 P-256 密钥对").set_defaults(func=cmd_keygen)

    sp = sub.add_parser("sign", help="对 manifest.json 签名，产出 .sig")
    sp.add_argument("manifest")
    sp.set_defaults(func=cmd_sign)

    vp = sub.add_parser("verify", help="用公钥校验签名")
    vp.add_argument("manifest")
    vp.set_defaults(func=cmd_verify)

    args = p.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
