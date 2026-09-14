import argparse
import asyncio
import json
import logging

from bunbird.config import ControlConfig, FeishuConfig


def main():
    parser = argparse.ArgumentParser(prog="bunbird")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("bridge", help="连接飞书并发送队列中的回复")
    sub.add_parser("worker", help="领取任务并调用模型")
    sub.add_parser("tasks", help="列出最近任务（管理员）")
    for name in ("inspect", "cancel"):
        sub.add_parser(name).add_argument("task_id")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    logging.getLogger("httpx").setLevel(logging.WARNING)
    try:
        if args.command == "bridge":
            from bunbird.feishu import Bridge

            Bridge(FeishuConfig.load(), ControlConfig.load("bridge")).run()
        elif args.command == "worker":
            from bunbird.model import Model, ModelConfig
            from bunbird.worker import run

            config = ControlConfig.load("worker")
            asyncio.run(run(config, Model(ModelConfig.load())))
        else:
            from bunbird.control import client

            with client(ControlConfig.load("admin")) as http:
                if args.command == "tasks":
                    response = http.get("/api/tasks")
                elif args.command == "cancel":
                    response = http.post(f"/api/tasks/{args.task_id}/cancel")
                else:
                    response = http.get(f"/api/tasks/{args.task_id}")
                response.raise_for_status()
                print(json.dumps(response.json(), ensure_ascii=False, indent=2))
    except KeyboardInterrupt:
        pass
    except ValueError as error:
        parser.exit(2, str(error) + "\n")
    except Exception as error:
        parser.exit(1, f"启动或请求失败（{type(error).__name__}），请检查配置和服务状态。\n")


if __name__ == "__main__":
    main()
