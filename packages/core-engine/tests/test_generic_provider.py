import asyncio
from types import SimpleNamespace

from providers import get_provider
from providers.generic import GenericOpenAIProvider


def test_nvidia_provider_disables_native_structured_output() -> None:
    provider = get_provider("nvidia", model_override="z-ai/glm4.7")
    assert provider.supports_native_structured_output() is False


def test_generic_provider_stream_chat_separates_reasoning_content() -> None:
    provider = GenericOpenAIProvider(
        base_url="https://example.invalid/v1",
        api_key="test-key",
        default_model="test-model",
    )

    async def fake_create(**_request):
        async def _stream():
            yield SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        delta=SimpleNamespace(content=None, reasoning_content="The user wants to rename ")
                    )
                ]
            )
            yield SimpleNamespace(
                choices=[
                    SimpleNamespace(
                        delta=SimpleNamespace(content='{"action":"final"}', reasoning_content=None)
                    )
                ]
            )

        return _stream()

    provider.client = SimpleNamespace(
        chat=SimpleNamespace(
            completions=SimpleNamespace(create=fake_create)
        )
    )

    async def collect() -> list[object]:
        chunks: list[object] = []
        async for chunk in provider.stream_chat([{"role": "user", "content": "hi"}]):
            chunks.append(chunk)
        return chunks

    chunks = asyncio.run(collect())
    assert chunks == [
        {"reasoning": "The user wants to rename "},
        {"content": '{"action":"final"}'},
    ]


def test_generic_provider_stream_chat_passes_max_tokens() -> None:
    provider = GenericOpenAIProvider(
        base_url="https://example.invalid/v1",
        api_key="test-key",
        default_model="test-model",
    )
    captured_request: dict[str, object] = {}

    async def fake_create(**request):
        captured_request.update(request)

        async def _stream():
            if False:
                yield None

        return _stream()

    provider.client = SimpleNamespace(
        chat=SimpleNamespace(
            completions=SimpleNamespace(create=fake_create)
        )
    )

    async def collect() -> None:
        async for _chunk in provider.stream_chat(
            [{"role": "user", "content": "hi"}],
            max_tokens=2048,
        ):
            pass

    asyncio.run(collect())
    assert captured_request["max_tokens"] == 2048
