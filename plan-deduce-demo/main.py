from pathlib import Path


def main() -> None:
    summary_path = Path(__file__).parent / "summary.txt"

    if not summary_path.exists():
        raise FileNotFoundError("未找到 summary.txt，请确认该文件位于项目根目录。")

    content = summary_path.read_text(encoding="utf-8")
    print(content)


if __name__ == "__main__":
    main()