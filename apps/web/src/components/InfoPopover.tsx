import React, { useState, useRef, useEffect } from 'react';
import { Info, X, HelpCircle, ShieldCheck } from 'lucide-react';

export interface InfoPopoverProps {
  title: string;
  description?: React.ReactNode;
  bullets?: string[];
  bulletPoints?: string[];
  badge?: string;
  ariaLabel?: string;
  align?: 'left' | 'right' | 'center';
  buttonText?: string;
  iconSize?: number;
  className?: string;
  style?: React.CSSProperties;
  testId?: string;
}

export const InfoPopover: React.FC<InfoPopoverProps> = ({
  title,
  description,
  bullets,
  bulletPoints,
  badge,
  ariaLabel,
  align = 'left',
  buttonText,
  iconSize = 14,
  className = '',
  style,
  testId,
}) => {
  const [isOpen, setIsOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const popoverRef = useRef<HTMLDivElement>(null);

  // Close when clicking outside or pressing Escape
  useEffect(() => {
    if (!isOpen) return;

    const handleClickOutside = (e: MouseEvent) => {
      if (
        popoverRef.current &&
        !popoverRef.current.contains(e.target as Node) &&
        triggerRef.current &&
        !triggerRef.current.contains(e.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setIsOpen(false);
        triggerRef.current?.focus();
      }
    };

    window.addEventListener('mousedown', handleClickOutside);
    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('mousedown', handleClickOutside);
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  const toggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    setIsOpen((prev) => !prev);
  };

  return (
    <div
      className={`info-popover-container ${className}`}
      style={{
        position: 'relative',
        display: 'inline-flex',
        alignItems: 'center',
        verticalAlign: 'middle',
        ...style,
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        onClick={toggle}
        className={`info-trigger-btn ${isOpen ? 'active' : ''}`}
        aria-label={ariaLabel || `${title} 도움말 및 설명 보기`}
        aria-expanded={isOpen}
        title={ariaLabel || `${title} 상세 안내 보기`}
        data-testid={testId}
      >
        <span className="info-icon-circle">
          <Info size={iconSize} strokeWidth={2.4} />
        </span>
        {buttonText && <span className="info-btn-text">{buttonText}</span>}
      </button>

      {isOpen && (
        <div
          ref={popoverRef}
          role="dialog"
          aria-modal="true"
          aria-label={title}
          className={`info-popover-card align-${align}`}
        >
          <div className="info-popover-header">
            <div className="info-popover-title-row">
              <span className="info-popover-badge-icon">
                <HelpCircle size={16} />
              </span>
              <h4 className="info-popover-title">{title}</h4>
              {badge && <span className="info-popover-badge">{badge}</span>}
            </div>
            <button
              type="button"
              onClick={() => setIsOpen(false)}
              className="info-popover-close-btn"
              aria-label="안내 닫기"
            >
              <X size={15} />
            </button>
          </div>

          <div className="info-popover-body">
            {description && <div className="info-popover-desc">{description}</div>}

            {(bullets || bulletPoints) && (bullets || bulletPoints)!.length > 0 && (
              <ul className="info-popover-bullets">
                {(bullets || bulletPoints)!.map((b, idx) => (
                  <li key={idx}>
                    <ShieldCheck size={13} className="info-bullet-icon" />
                    <span>{b}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div className="info-popover-footer">
            <span className="info-popover-hint">확인 후 창 바깥이나 닫기 버튼을 누르면 닫힙니다.</span>
            <button
              type="button"
              onClick={() => setIsOpen(false)}
              className="info-popover-ok-btn"
            >
              확인
            </button>
          </div>
        </div>
      )}
    </div>
  );
};
