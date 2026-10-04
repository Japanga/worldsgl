import os
import sys
import shutil
import winreg
import ctypes

# Configuration
EXTENSION = ".tpgproject"
PROG_ID = "TPGProject.File"
ICON_NAME = "app_icon.ico"  # .tpgproject icon
WGANIM_EXTENSION = ".wganim"
WGANIM_PROG_ID = "WGANIM.File"
WGANIM_ICON_NAME = "wganim.ico"  # .wganim icon expected in the script's folder

def is_admin():
    """Check if the script is running with administrative privileges."""
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except:
        return False

def setup_registry_icon():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    source_icon_path = os.path.join(current_dir, ICON_NAME)
    wganim_icon_path = os.path.join(current_dir, WGANIM_ICON_NAME)

    print("🔄 Configuring Windows file associations...")

    try:
        # .tpgproject association/icon
        if os.path.exists(source_icon_path):
            with winreg.CreateKey(winreg.HKEY_CLASSES_ROOT, EXTENSION) as key:
                winreg.SetValue(key, "", winreg.REG_SZ, PROG_ID)
            with winreg.CreateKey(winreg.HKEY_CLASSES_ROOT, f"{PROG_ID}\\DefaultIcon") as key:
                winreg.SetValue(key, "", winreg.REG_SZ, source_icon_path)
            print(f"✅ {EXTENSION} icon mapped to: {source_icon_path}")
        else:
            print(f"⚠️ '{ICON_NAME}' was not found; leaving {EXTENSION} unchanged.")

        # .wganim association/icon
        if not os.path.exists(wganim_icon_path):
            print(f"❌ Error: '{WGANIM_ICON_NAME}' not found in the script folder ({current_dir}).")
            print("Please place wganim.ico beside this script and run it again.")
            return
        with winreg.CreateKey(winreg.HKEY_CLASSES_ROOT, WGANIM_EXTENSION) as key:
            winreg.SetValue(key, "", winreg.REG_SZ, WGANIM_PROG_ID)
        with winreg.CreateKey(winreg.HKEY_CLASSES_ROOT, f"{WGANIM_PROG_ID}\\DefaultIcon") as key:
            winreg.SetValue(key, "", winreg.REG_SZ, wganim_icon_path)
        print(f"✅ {WGANIM_EXTENSION} icon mapped to: {wganim_icon_path}")
        print("💡 You may need to restart Windows Explorer or log out/in for icons to refresh.")
    except PermissionError:
        print("❌ Error: Permission denied. You must run this script as an Administrator.")
    except Exception as e:
        print(f"❌ An unexpected error occurred: {e}")

if __name__ == "__main__":
    if not is_admin():
        print("🔒 Re-launching script with Administrator privileges...")
        # Re-run the script with admin rights
        ctypes.windll.shell32.ShellExecuteW(None, "runas", sys.executable, " ".join(sys.argv), None, 1)
    else:
        setup_registry_icon()
        input("\nPress Enter to exit...")
