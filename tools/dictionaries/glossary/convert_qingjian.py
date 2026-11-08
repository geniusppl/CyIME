"""把青简的英语释义表（glossary-en.tsv）转成 CyIME 释义词典格式。

输入：青简 assets/glossary/glossary-en.tsv（GPL-3.0-or-later，https://github.com/qingjian-team/qingjian ），
每行 `词\t[词性. ]译词[|读音]\t…`，LLM（DeepSeek）生成。
输出：CyIME 的 `词\t释义`（按 UTF-8 字节序、键唯一，开头 # 行写来源与许可），
释义只取第一个义项，保留词性前缀（n. chew），App 上屏英文时再去掉词性。

用法：python -I convert_qingjian.py glossary-en.tsv qingjian_gloss_en.tsv
"""
import sys


def main(src: str, dst: str) -> None:
    entries: dict[str, str] = {}
    with open(src, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 2:
                continue
            word = parts[0].strip()
            gloss = parts[1].split("|", 1)[0].strip()
            if not word or not gloss or "\t" in gloss:
                continue
            entries.setdefault(word, gloss)
    keys = sorted(entries, key=lambda k: k.encode("utf-8"))
    with open(dst, "w", encoding="utf-8", newline="\n") as out:
        out.write("# 青简英语释义表（qingjian-team/qingjian assets/glossary/glossary-en.tsv），LLM 生成\n")
        out.write("# 许可：GPL-3.0-or-later，https://github.com/qingjian-team/qingjian\n")
        for k in keys:
            out.write(f"{k}\t{entries[k]}\n")
    print(f"{len(keys)} entries -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
