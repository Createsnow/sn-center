from pydantic import Field

from app.schemas.common import In


class LoginIn(In):
    emp_no: str | None = Field(None, examples=["admin"])
    password: str | None = Field(None, examples=["Admin@123"])


class PasswordIn(In):
    old_password: str | None = None
    new_password: str | None = None


class LangIn(In):
    """用户语言偏好；None 表示清除偏好、跟随浏览器。"""

    lang: str | None = None
