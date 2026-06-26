import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/solonclaw/chat'
import { useSessionSearch } from './useSessionSearch'

export function useKeyboard() {
  const router = useRouter()
  const chatStore = useChatStore()
  const { sessionSearchOpen, openSessionSearch, closeSessionSearch } = useSessionSearch()

  function handleKeydown(e: KeyboardEvent) {
    const mod = e.ctrlKey || e.metaKey

    if (mod && e.key === 'n') {
      e.preventDefault()
      chatStore.newChat()
      return
    }

    if (mod && e.key === 'j') {
      e.preventDefault()
      router.push({ name: 'solonclaw.jobs' })
      return
    }

    if (mod && e.key.toLowerCase() === 'k') {
      if (router.currentRoute.value.name === 'login') return
      e.preventDefault()
      openSessionSearch()
      return
    }

    if (e.key === 'Escape') {
      if (sessionSearchOpen.value) {
        e.preventDefault()
        closeSessionSearch()
        return
      }
      // Esc 优先关闭当前 Antdv 弹窗，避免聊天快捷键吞掉弹窗交互。
      const modal = document.querySelector('.ant-modal-root')
      if (modal) {
        const closeBtn = modal.querySelector('.ant-modal-close') as HTMLElement
        closeBtn?.click()
      }
    }
  }

  onMounted(() => {
    window.addEventListener('keydown', handleKeydown)
  })

  onUnmounted(() => {
    window.removeEventListener('keydown', handleKeydown)
  })
}
