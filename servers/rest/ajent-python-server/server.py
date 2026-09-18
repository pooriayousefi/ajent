import json
import subprocess
import os
import tempfile
from http.server import BaseHTTPRequestHandler, HTTPServer

class PythonToolHandler(BaseHTTPRequestHandler):
    
    def _send_response(self, status, response_dict):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps(response_dict).encode('utf-8'))

    def do_GET(self):
        if self.path == "/schema":
            schema = [{
                "type": "function",
                "function": {
                    "name": "execute_python",
                    "description": "Executes Python 3 code in a secure environment. Pre-installed libraries include: numpy, pandas, scipy, matplotlib, pymupdf (PDF), python-docx (Word), python-pptx (PowerPoint), openpyxl (Excel), requests, beautifulsoup4, arabic-reshaper, python-bidi. To save generated files so the user can access them, ALWAYS save them to the '/app/output' directory. Print text results to stdout.",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "code": {
                                "type": "string",
                                "description": "The complete Python 3 code to execute. Ensure you print() any results you want to see."
                            }
                        },
                        "required": ["code"]
                    }
                }
            }]
            self._send_response(200, schema)
        else:
            self._send_response(404, {"error": "Not found"})

    def do_POST(self):
        if self.path == "/tools/call":
            content_length = int(self.headers['Content-Length'])
            body = self.rfile.read(content_length).decode('utf-8')
            
            try:
                data = json.loads(body)
                tool_name = data.get("name")
                args = data.get("arguments", {})
                
                if tool_name == "execute_python":
                    code = args.get("code", "")
                    
                    # Write code to a temporary file and execute it
                    with tempfile.NamedTemporaryFile(mode="w", suffix=".py", delete=False) as f:
                        f.write(code)
                        temp_file_path = f.name
                    
                    # Execute the script securely inside the container
                    result = subprocess.run(
                        ["python3", temp_file_path],
                        capture_output=True,
                        text=True,
                        timeout=60  # Kill script after 60 seconds to prevent infinite loops
                    )
                    os.remove(temp_file_path)
                    
                    output = result.stdout
                    if result.returncode != 0:
                        # Include stderr if the script failed
                        output += f"\n[ERROR]: {result.stderr}"
                        
                    self._send_response(200, {"result": output, "is_error": result.returncode != 0})
                else:
                    self._send_response(200, {"error": f"Unknown tool: {tool_name}", "is_error": True})
                    
            except Exception as e:
                self._send_response(500, {"error": str(e), "is_error": True})
        else:
            self._send_response(404, {"error": "Not found"})

if __name__ == "__main__":
    server = HTTPServer(('0.0.0.0', 4007), PythonToolHandler)
    print("Python Tool REST server running on port 4007...")
    server.serve_forever()