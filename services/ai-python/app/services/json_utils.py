import json
import re
from typing import Any


def extract_json(text: str) -> Any:
    if not text or not isinstance(text, str):
        raise ValueError("Empty or invalid text for JSON extraction")

    # Work on a copy
    cleaned = text.strip()

    # Remove markdown fences: ```json ... ``` or ``` ... ```
    # Pattern handles optional language
    fence_pattern = re.compile(r"^```(?:json|JSON)?\s*\n?(.*?)\n?```$", re.DOTALL)
    m = fence_pattern.match(cleaned)
    if m:
        cleaned = m.group(1).strip()

    # Also handle inline fences if present
    if cleaned.count("```") >= 2:
        # extract content between first and last fence
        parts = cleaned.split("```")
        if len(parts) >= 3:
            # middle parts: maybe lang + content + rest? take middle content
            # try to find json-like content
            for part in parts[1:-1]:
                # skip language token if it's a single word at start
                p = part.strip()
                # remove leading lang
                lines = p.splitlines()
                if len(lines) > 0 and re.match(r"^(json|JSON|text|txt)?$", lines[0].strip()):
                    p2 = "\n".join(lines[1:]).strip()
                else:
                    p2 = p
                if p2 and (p2.startswith("{") or p2.startswith("[")):
                    cleaned = p2
                    break

    # Remove <think>...</think> blocks (including multiline)
    think_pattern = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
    cleaned = think_pattern.sub("", cleaned).strip()

    # Try direct json.loads
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        pass

    # Extract from first { or [ to matching } or ]
    # Find first brace/bracket
    first_obj = cleaned.find("{")
    first_arr = cleaned.find("[")
    candidates = []
    if first_obj != -1:
        candidates.append(("obj", first_obj))
    if first_arr != -1:
        candidates.append(("arr", first_arr))
    if not candidates:
        raise ValueError("No JSON object or array found in text")

    # Prefer an object; fall back to an array only if no object parses
    candidates.sort(key=lambda x: x[0] != "obj")
    for _, start in candidates:
        # Find matching closing
        stack = []
        in_str = False
        escape = False
        quote_char = None
        for i, ch in enumerate(cleaned[start:], start=start):
            if in_str:
                if escape:
                    escape = False
                    continue
                if ch == "\\":
                    escape = True
                    continue
                if ch == quote_char:
                    in_str = False
                    quote_char = None
                continue
            else:
                if ch in ('"', "'"):
                    in_str = True
                    quote_char = ch
                    continue
                if ch == "{" or ch == "[":
                    stack.append(ch)
                elif ch == "}" or ch == "]":
                    if not stack:
                        break  # unbalanced, stop
                    top = stack.pop()
                    if (top == "{" and ch != "}") or (top == "[" and ch != "]"):
                        # try to continue? but likely not valid
                        pass
                    if len(stack) == 0:
                        end = i
                        json_candidate = cleaned[start:end + 1]
                        try:
                            return json.loads(json_candidate)
                        except json.JSONDecodeError:
                            break

    raise ValueError("Could not extract valid JSON from text")