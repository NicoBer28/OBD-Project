.PHONY: format check test

format:
	@echo "Formatting Flutter..."
	cd app/obd_app && dart format .

	@echo "Formatting firmware..."
	cd hardware && clang-format -i main/*.cpp

check: format
	@echo "Checking Flutter..."
	cd app/obd_app && dart format --output=none --set-exit-if-changed .
	cd app/obd_app && flutter analyze --no-fatal-infos --no-fatal-warnings
	cd app/obd_app && flutter test

	@echo "Checking firmware formatting..."
	cd hardware && clang-format --dry-run --Werror main/*.cpp

test:
	@echo "Running Flutter tests..."
	cd app/obd_app && flutter test

setup:
	chmod -R u+x ./.githooks 
	lefthook install
	@echo "Git hooks installed successfully."