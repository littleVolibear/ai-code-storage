from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Query
from fastapi.routing import APIRoute
from pydantic import BaseModel, Field


class MessageResponse(BaseModel):
    message: str = Field(..., description="返回消息")


class HealthResponse(BaseModel):
    status: str = Field(..., description="服务状态")
    service: str = Field(..., description="服务名称")


class UserCreate(BaseModel):
    name: str = Field(..., min_length=1, max_length=50, description="用户姓名")
    email: str = Field(..., min_length=3, max_length=100, description="用户邮箱")


class User(BaseModel):
    id: int = Field(..., description="用户ID")
    name: str = Field(..., description="用户姓名")
    email: str = Field(..., description="用户邮箱")


app = FastAPI(
    title="接口信息汇总示例项目",
    description="启动时自动总结所有接口信息，并写入根目录 a.txt。",
    version="1.0.0",
)

users: List[User] = [
    User(id=1, name="张三", email="zhangsan@example.com"),
    User(id=2, name="李四", email="lisi@example.com"),
]


@app.get(
    "/",
    response_model=MessageResponse,
    summary="服务欢迎信息",
    description="返回服务基本信息，用于确认服务是否可访问。",
)
def root() -> MessageResponse:
    return MessageResponse(message="接口信息汇总示例项目运行中")


@app.get(
    "/health",
    response_model=HealthResponse,
    summary="健康检查",
    description="返回服务健康状态。",
)
def health() -> HealthResponse:
    return HealthResponse(status="ok", service="interface-summary-demo")


@app.get(
    "/api/users",
    response_model=List[User],
    summary="查询用户列表",
    description="分页查询用户列表。",
)
def list_users(
    offset: int = Query(0, ge=0, description="跳过的数据条数"),
    limit: int = Query(10, ge=1, le=100, description="返回的数据条数"),
) -> List[User]:
    return users[offset : offset + limit]


@app.post(
    "/api/users",
    response_model=User,
    summary="创建用户",
    description="根据请求体创建新用户。",
)
def create_user(payload: UserCreate) -> User:
    next_id = max((user.id for user in users), default=0) + 1
    user = User(id=next_id, name=payload.name, email=payload.email)
    users.append(user)
    generate_interface_summary()
    return user


@app.get(
    "/api/users/{user_id}",
    response_model=User,
    summary="查询用户详情",
    description="根据用户ID查询单个用户详情。",
)
def get_user(user_id: int) -> User:
    for user in users:
        if user.id == user_id:
            return user
    raise HTTPException(status_code=404, detail="用户不存在")


@app.delete(
    "/api/users/{user_id}",
    response_model=MessageResponse,
    summary="删除用户",
    description="根据用户ID删除用户。",
)
def delete_user(user_id: int) -> MessageResponse:
    for index, user in enumerate(users):
        if user.id == user_id:
            users.pop(index)
            generate_interface_summary()
            return MessageResponse(message="删除成功")
    raise HTTPException(status_code=404, detail="用户不存在")


def _get_model_name(model: Any) -> str:
    if model is None:
        return "无"
    return getattr(model, "__name__", str(model))


def _format_params(params: List[Any]) -> str:
    if not params:
        return "无"

    result: List[str] = []
    for param in params:
        name = getattr(param, "name", "unknown")
        required = getattr(param, "required", False)
        default = getattr(param, "default", None)
        type_name = "未知"

        field_info = getattr(param, "field_info", None)
        annotation = getattr(field_info, "annotation", None)
        if annotation is not None:
            type_name = getattr(annotation, "__name__", str(annotation))
        else:
            type_ = getattr(param, "type_", None)
            if type_ is not None:
                type_name = getattr(type_, "__name__", str(type_))

        if required:
            result.append(f"{name}: {type_name}，必填")
        else:
            result.append(f"{name}: {type_name}，非必填，默认值={default}")

    return "；".join(result)


def _format_body(route: APIRoute) -> str:
    body_field = getattr(route, "body_field", None)
    if body_field is None:
        return "无"

    type_ = getattr(body_field, "type_", None)
    if type_ is not None:
        return getattr(type_, "__name__", str(type_))

    return getattr(body_field, "name", "请求体")


def get_interface_info() -> List[Dict[str, str]]:
    interface_info: List[Dict[str, str]] = []

    routes = [
        route
        for route in app.routes
        if isinstance(route, APIRoute) and getattr(route, "include_in_schema", True)
    ]

    routes.sort(key=lambda route: (route.path, sorted(route.methods or [])))

    for route in routes:
        methods = sorted(route.methods or [])
        response_model = _get_model_name(getattr(route, "response_model", None))

        interface_info.append(
            {
                "methods": ", ".join(methods),
                "path": route.path,
                "name": route.name,
                "summary": route.summary or "无",
                "description": route.description or "无",
                "path_params": _format_params(route.dependant.path_params),
                "query_params": _format_params(route.dependant.query_params),
                "body": _format_body(route),
                "response_model": response_model,
            }
        )

    return interface_info


def generate_interface_summary() -> None:
    lines: List[str] = [
        "接口信息汇总",
        "",
        "说明：本文件由项目自动生成，汇总当前 FastAPI 应用中所有对外接口信息。",
        "",
    ]

    for index, info in enumerate(get_interface_info(), start=1):
        lines.extend(
            [
                f"{index}. {info['methods']} {info['path']}",
                f"名称: {info['name']}",
                f"摘要: {info['summary']}",
                f"说明: {info['description']}",
                f"路径参数: {info['path_params']}",
                f"查询参数: {info['query_params']}",
                f"请求体: {info['body']}",
                f"响应模型: {info['response_model']}",
                "",
            ]
        )

    Path("a.txt").write_text("\n".join(lines), encoding="utf-8")


@app.on_event("startup")
def on_startup() -> None:
    generate_interface_summary()


generate_interface_summary()