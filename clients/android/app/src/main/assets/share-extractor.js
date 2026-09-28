(function () {
  // Read rendered message nodes only. No private APIs, login state, or broad body-text fallback.
  const rows = [];
  const text = el => (el && (el.innerText || el.textContent) || '').trim();
  const add = (role, el) => { const value = text(el); if (value) rows.push({role, text: value}); };
  const host = location.hostname.toLowerCase();
  if (host === 'chat.deepseek.com' || host === 'deepseek.com') {
    document.querySelectorAll('.ds-message').forEach(el => {
      const answer = el.querySelector('.ds-assistant-message-main-content');
      const question = el.querySelector('.ds-collapsible-text');
      if (answer) add('assistant', answer); else if (question) add('user', question);
    });
  } else if (host === 'chatgpt.com' || host === 'www.chatgpt.com' || host === 'chat.openai.com') {
    document.querySelectorAll('[data-message-author-role]').forEach(el => {
      if (el.parentElement && el.parentElement.closest('[data-message-author-role]')) return;
      const role = el.getAttribute('data-message-author-role');
      if (role === 'user') add(role, el.querySelector('.whitespace-pre-wrap') || el);
      if (role === 'assistant') { const content = el.querySelector('.markdown'); if (content) add(role, content); }
    });
  } else if (host === 'kimi.com' || host === 'www.kimi.com' || host === 'kimi.ai' || host === 'www.kimi.ai' || host === 'kimi.moonshot.cn') {
    document.querySelectorAll('.chat-content-item').forEach(el => {
      if (el.classList.contains('chat-content-item-user')) add('user', el.querySelector('.user-content') || el);
      else if (el.classList.contains('chat-content-item-assistant')) {
        const content = el.querySelector('.markdown'); if (content) add('assistant', content);
      }
    });
  } else if (host === 'doubao.com' || host === 'www.doubao.com' || host === 'v.doubao.com') {
    document.querySelectorAll('[data-testid="send_message"], [data-testid="receive_message"], [class*="bg-g-send-msg-bubble"], [class*="bg-g-receive-msg-bubble"]').forEach(el => {
      if (el.parentElement && el.parentElement.closest('[data-testid="send_message"], [data-testid="receive_message"], [class*="bg-g-send-msg-bubble"], [class*="bg-g-receive-msg-bubble"]')) return;
      if (el.getAttribute('data-testid') === 'send_message' || String(el.className).includes('bg-g-send-msg-bubble')) add('user', el);
      else { const content = el.querySelector('[class*="markdown"]'); if (content) add('assistant', content); }
    });
  }
  const size = rows.reduce((n, row) => n + row.text.length, 0);
  return JSON.stringify({messages: size <= 120000 && rows.length <= 100 ? rows : [], oversized: size > 120000 || rows.length > 100});
})()
