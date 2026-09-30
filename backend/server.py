#!/usr/bin/env python3
"""Small ARIA backend boundary. Provider keys stay in the server environment."""
import json, os, time, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
HOST=os.getenv('ARIA_BACKEND_HOST','0.0.0.0'); PORT=int(os.getenv('ARIA_BACKEND_PORT','8787')); MAX_BODY=256*1024
class ProviderError(Exception):
    def __init__(self,status,message): super().__init__(message); self.status=status
class LlmProvider:
    def generate(self,message,max_tokens): raise NotImplementedError
class MockProvider(LlmProvider):
    def generate(self,message,max_tokens): return 'Recibí tu solicitud mediante el backend ARIA. La conexión Cloud está funcionando.'
class OpenAICompatibleProvider(LlmProvider):
    def __init__(self):
        self.url=os.environ['ARIA_LLM_BASE_URL'].rstrip('/')+'/chat/completions'; self.api_key=os.environ['ARIA_LLM_API_KEY']; self.model=os.getenv('ARIA_LLM_MODEL','gpt-4o-mini'); self.timeout=float(os.getenv('ARIA_LLM_TIMEOUT_SECONDS','90'))
    def generate(self,message,max_tokens):
        body=json.dumps({'model':self.model,'messages':[{'role':'user','content':message}],'max_tokens':max_tokens}).encode()
        req=urllib.request.Request(self.url,body,{'Authorization':'Bearer '+self.api_key,'Content-Type':'application/json'},method='POST')
        try:
            with urllib.request.urlopen(req,timeout=self.timeout) as response: payload=json.load(response)
        except urllib.error.HTTPError as error: raise ProviderError(429 if error.code==429 else 502,'provider_request_failed') from error
        except (urllib.error.URLError,TimeoutError): raise ProviderError(504,'provider_timeout')
        try: text=payload['choices'][0]['message']['content'].strip()
        except (KeyError,IndexError,TypeError,AttributeError) as error: raise ProviderError(502,'provider_invalid_response') from error
        if not text: raise ProviderError(502,'provider_empty_response')
        return text
def provider_from_environment():
    name=os.getenv('ARIA_LLM_PROVIDER','mock').lower()
    if name=='mock': return MockProvider(),'mock'
    if name in ('openai','openai-compatible'):
        if not os.getenv('ARIA_LLM_API_KEY') or not os.getenv('ARIA_LLM_BASE_URL'): raise RuntimeError('ARIA_LLM_API_KEY and ARIA_LLM_BASE_URL are required')
        return OpenAICompatibleProvider(),os.getenv('ARIA_LLM_MODEL','gpt-4o-mini')
    raise RuntimeError('unsupported_provider')
try: PROVIDER,PROVIDER_NAME=provider_from_environment(); STARTUP_ERROR=None
except Exception as error: PROVIDER,PROVIDER_NAME,STARTUP_ERROR=None,'unavailable',str(error)
class Handler(BaseHTTPRequestHandler):
    server_version='AriaBackend/1'
    def log_message(self,fmt,*args): print('%s - %s'%(self.address_string(),fmt%args))
    def send_json(self,status,payload):
        raw=json.dumps(payload,ensure_ascii=False).encode(); self.send_response(status); self.send_header('Content-Type','application/json; charset=utf-8'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if self.path=='/v1/health': return self.send_json(200 if STARTUP_ERROR is None else 503,{'status':'ok' if STARTUP_ERROR is None else 'unavailable','protocol':1,'provider':PROVIDER_NAME})
        self.send_json(404,{'error':'not_found'})
    def do_POST(self):
        if self.path!='/v1/brain/respond': return self.send_json(404,{'error':'not_found'})
        request_id=None
        try:
            if self.headers.get('X-ARIA-Protocol')!='1': return self.send_json(400,{'error':'unsupported_protocol'})
            length=int(self.headers.get('Content-Length','0'))
            if length<=0 or length>MAX_BODY: return self.send_json(400,{'error':'invalid_body'})
            request=json.loads(self.rfile.read(length).decode()); request_id=request.get('requestId'); message=request.get('message'); generation=request.get('generation',{})
            if not isinstance(request_id,str) or not request_id.strip() or len(request_id)>100: return self.send_json(400,{'error':'request_id_required'})
            if not isinstance(message,str) or not message.strip() or len(message)>MAX_BODY: return self.send_json(400,{'error':'message_required'})
            max_tokens=generation.get('maxOutputTokens',384)
            if not isinstance(max_tokens,int) or isinstance(max_tokens,bool) or not 1<=max_tokens<=4096: return self.send_json(400,{'error':'invalid_generation'})
            if STARTUP_ERROR is not None or PROVIDER is None: return self.send_json(503,{'error':'provider_unavailable','requestId':request_id})
            started=time.monotonic(); reply=PROVIDER.generate(message,max_tokens)
            return self.send_json(200,{'requestId':request_id,'reply':reply,'model':PROVIDER_NAME,'finishReason':'stop','serverProcessingMs':int((time.monotonic()-started)*1000)})
        except ProviderError as error: return self.send_json(error.status,{'error':str(error),**({'requestId':request_id} if request_id else {})})
        except (json.JSONDecodeError,UnicodeDecodeError,ValueError): return self.send_json(400,{'error':'invalid_json'})
        except Exception: return self.send_json(500,{'error':'internal_error',**({'requestId':request_id} if request_id else {})})
if __name__=='__main__': print(f'ARIA backend listening on {HOST}:{PORT} ({PROVIDER_NAME})'); ThreadingHTTPServer((HOST,PORT),Handler).serve_forever()
