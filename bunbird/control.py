import httpx

from bunbird.config import ControlConfig


def client(config: ControlConfig, timeout: float = 5) -> httpx.Client:
    return httpx.Client(
        base_url=config.url,
        headers={"Authorization": f"Bearer {config.token}"},
        timeout=timeout,
        trust_env=False,
    )


def async_client(config: ControlConfig) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=config.url,
        headers={"Authorization": f"Bearer {config.token}"},
        timeout=5,
        trust_env=False,
    )
