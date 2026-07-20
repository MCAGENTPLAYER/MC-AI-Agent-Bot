from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    ai_api_key: str = ""
    ai_api_url: str = "https://api.deepseek.com"
    ai_model: str = "deepseek-chat"
    server_host: str = "0.0.0.0"
    server_port: int = 8080
    log_level: str = "info"

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    @property
    def data_dir(self) -> Path:
        return Path("data")

    @property
    def memory_dir(self) -> Path:
        return self.data_dir / "memory"
    
    def update_api_key(self, new_key: str):
        """更新API密钥并保存到.env文件"""
        self.ai_api_key = new_key
        self._save_to_env()
    
    def update_api_url(self, new_url: str):
        """更新API URL并保存到.env文件"""
        self.ai_api_url = new_url
        self._save_to_env()
    
    def update_model(self, new_model: str):
        """更新模型名称并保存到.env文件"""
        self.ai_model = new_model
        self._save_to_env()
    
    def _save_to_env(self):
        """保存当前配置到.env文件"""
        env_path = Path(".env")
        env_content = f"""AI_API_KEY={self.ai_api_key}
AI_API_URL={self.ai_api_url}
AI_MODEL={self.ai_model}
SERVER_HOST={self.server_host}
SERVER_PORT={self.server_port}
LOG_LEVEL={self.log_level}
"""
        env_path.write_text(env_content, encoding="utf-8")


settings = Settings()
