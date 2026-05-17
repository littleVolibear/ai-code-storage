from pathlib import Path
from datetime import datetime


def generate_business_summary() -> str:
    """生成业务总结内容。"""
    today = datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    return f"""业务总结

生成时间：{today}

一、业务概况
本项目用于生成并保存业务总结内容，程序运行后会将业务总结写入项目根目录下的 ss.txt 文件中。

二、核心目标
1. 自动生成业务总结文本。
2. 将总结内容稳定写入指定文件。
3. 保证项目结构简单、可直接运行。

三、执行结果
业务总结已成功写入项目根目录 ss.txt。

四、后续建议
1. 可根据实际业务场景扩展总结模板。
2. 可接入数据库、接口或表格数据自动生成总结。
3. 可增加定时任务，实现每日、每周或每月自动输出业务总结。
"""


def write_summary_to_file(filename: str = "ss.txt") -> Path:
    """将业务总结写入项目根目录文件。"""
    project_root = Path(__file__).resolve().parent
    output_file = project_root / filename
    output_file.write_text(generate_business_summary(), encoding="utf-8")
    return output_file


def main() -> None:
    output_file = write_summary_to_file()
    print(f"业务总结已写入：{output_file}")


if __name__ == "__main__":
    main()