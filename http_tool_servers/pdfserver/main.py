import os
import io
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fpdf import FPDF
from pypdf import PdfReader, PdfWriter
import uvicorn

app = FastAPI()

# --- OpenAI Compatible Schema ---
@app.get("/schema")
async def get_schema():
    return [
        {
            "type": "function",
            "function": {
                "name": "create_pdf",
                "description": "Creates a new PDF file at the specified absolute path with the provided text. Overwrites the file if it already exists.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "The absolute file path of the PDF (e.g., '/Users/user/AjentWorkbench/report.pdf')."},
                        "text": {"type": "string", "description": "The text content to put into the PDF."}
                    },
                    "required": ["path", "text"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "read_pdf",
                "description": "Extracts and returns all text content from a specified PDF file.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "The absolute file path of the PDF to read."}
                    },
                    "required": ["path"]
                }
            }
        },
        {
            "type": "function",
            "function": {
                "name": "append_text_to_pdf",
                "description": "Appends a new page with text to an existing PDF file.",
                "parameters": {
                    "type": "object",
                    "properties": {
                        "path": {"type": "string", "description": "The absolute file path of the PDF to append to."},
                        "text": {"type": "string", "description": "The text content to add on the new page."}
                    },
                    "required": ["path", "text"]
                }
            }
        }
    ]

# --- Health Check ---
@app.get("/health")
async def health():
    return {"status": "healthy"}

# --- Helper to build a PDF in memory ---
def build_pdf_bytes(text: str) -> bytes:
    pdf = FPDF()
    pdf.add_page()
    pdf.set_font("Arial", size=12) # <-- I forgot this line!
    # FPDF2 handles standard text. We use multi_cell for automatic word wrap.
    pdf.multi_cell(0, 10, text)
    return pdf.output()

# --- Tool Execution Endpoint ---
@app.post("/tools/call")
async def tools_call(request: Request):
    try:
        body = await request.json()
        tool_name = body.get("name")
        args = body.get("arguments", {})

        if tool_name == "create_pdf":
            file_path = args.get("path")
            text = args.get("text")
            if not file_path or text is None:
                return {"error": "Missing path or text", "is_error": True}
            
            # Create parent directories if they don't exist
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            
            pdf_bytes = build_pdf_bytes(text)
            with open(file_path, "wb") as f:
                f.write(pdf_bytes)
                
            return {"result": f"Successfully created PDF at: {file_path}", "is_error": False}

        elif tool_name == "read_pdf":
            file_path = args.get("path")
            if not file_path:
                return {"error": "Missing path", "is_error": True}
            
            if not os.path.exists(file_path):
                return {"error": f"File not found: {file_path}", "is_error": True}
            
            reader = PdfReader(file_path)
            text = ""
            for page in reader.pages:
                text += page.extract_text() + "\n"
                
            return {"result": text, "is_error": False}

        elif tool_name == "append_text_to_pdf":
            file_path = args.get("path")
            text = args.get("text")
            if not file_path or text is None:
                return {"error": "Missing path or text", "is_error": True}
            
            if not os.path.exists(file_path):
                return {"error": f"File not found: {file_path}", "is_error": True}
            
            writer = PdfWriter()
            reader = PdfReader(file_path)
            for page in reader.pages:
                writer.add_page(page)
                
            new_page_pdf_bytes = build_pdf_bytes(text)
            new_page_reader = PdfReader(io.BytesIO(new_page_pdf_bytes))
            writer.add_page(new_page_reader.pages[0])
            
            with open(file_path, "wb") as f:
                writer.write(f)
                
            return {"result": f"Successfully appended text to PDF at: {file_path}", "is_error": False}

        else:
            return {"error": f"Unknown tool: {tool_name}", "is_error": True}

    except Exception as e:
        return {"error": f"Tool failed: {str(e)}", "is_error": True}

# --- Main Entry Point ---
if __name__ == "__main__":
    print("PDF Tool Server (Pure Python) running on http://localhost:4007")
    uvicorn.run(app, host="127.0.0.1", port=4007)