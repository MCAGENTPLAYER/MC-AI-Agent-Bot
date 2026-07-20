import logging
from pathlib import Path

from core.config import settings

logging.basicConfig(
    level=logging.getLevelName(settings.log_level.upper()),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)

logger = logging.getLogger(__name__)


def init_dirs():
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.memory_dir.mkdir(parents=True, exist_ok=True)
    logger.info("Initialized data directories")
