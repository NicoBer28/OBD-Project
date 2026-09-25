.PHONY: format format-flutter format-firmware \
        check check-flutter check-firmware \
        test setup verify

FLUTTER_DIR := app/obd_app
FIRMWARE_DIR := hardware
GITHOOKS_DIR := .githooks

# ---------------------------------------------------------------------------
# Formatting
# ---------------------------------------------------------------------------

format: format-flutter format-firmware

format-flutter:
	@echo "Formatting Flutter..."
	cd $(FLUTTER_DIR) && dart format .

format-firmware:
	@echo "Formatting firmware..."
	cd $(FIRMWARE_DIR) && clang-format -i main/*.cpp

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

check: check-flutter check-firmware

check-flutter:
	@echo "Checking Flutter formatting..."
	cd $(FLUTTER_DIR) && dart format --output=none --set-exit-if-changed .

	@echo "Running Flutter analyzer..."
	cd $(FLUTTER_DIR) && flutter analyze --no-fatal-infos --no-fatal-warnings

check-firmware:
	@echo "Checking firmware formatting..."
	cd $(FIRMWARE_DIR) && clang-format --dry-run --Werror main/*.cpp

# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

test:
	@echo "Running Flutter tests..."
	cd $(FLUTTER_DIR) && flutter test

# ---------------------------------------------------------------------------
# Development setup
# ---------------------------------------------------------------------------

setup:
	@echo "Installing Git hooks..."
	chmod u+x $(GITHOOKS_DIR)/*
	lefthook install
	@echo "Git hooks installed successfully."