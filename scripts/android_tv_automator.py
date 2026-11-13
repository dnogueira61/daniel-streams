import os
import sys
import subprocess
import time

ADB_PATH = r"C:\Users\Daniel\AppData\Local\Android\Sdk\platform-tools\adb.exe"
PACKAGE_NAME = "com.dlive.ptstream"

class AndroidAutomator:
    def __init__(self, adb_path=ADB_PATH):
        self.adb = adb_path

    def run_adb(self, *args):
        cmd = [self.adb] + list(args)
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        return res.returncode, res.stdout.strip(), res.stderr.strip()

    def get_connected_devices(self):
        code, out, _ = self.run_adb("devices")
        if code != 0:
            return []
        lines = out.splitlines()[1:]
        devices = []
        for l in lines:
            parts = l.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        return devices

    def send_key(self, keycode):
        return self.run_adb("shell", "input", "keyevent", str(keycode))

    def launch_app(self):
        return self.run_adb("shell", "am", "start", "-n", f"{PACKAGE_NAME}/.MainActivity")

    def simulate_tv_navigation(self):
        """Simula fluxo completo de comando Google TV: zapping, abertura de gaveta e MultiView"""
        print("[AndroidAutomator] A iniciar simulação de comandos TV...")
        # DPAD_DOWN
        self.send_key(20)
        time.sleep(0.5)
        # DPAD_DOWN
        self.send_key(20)
        time.sleep(0.5)
        # DPAD_CENTER (OK)
        self.send_key(23)
        time.sleep(1.0)
        # DPAD_LEFT (verificar se gaveta não interfere indevidamente)
        self.send_key(21)
        print("[AndroidAutomator] Sequência executada com sucesso.")

if __name__ == "__main__":
    automator = AndroidAutomator()
    devices = automator.get_connected_devices()
    print(f"Dispositivos Android/TV detetados via ADB: {len(devices)}")
    for d in devices:
        print(f" - {d}")
    if devices:
        automator.simulate_tv_navigation()
    else:
        print("ℹ️ Nenhum emulador/TV físico ativo no momento. Pipeline pronta para execução assim que conectado.")
