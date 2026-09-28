import axios from 'axios';
import { removeToken, getToken } from '../utils/auth';

const getBaseURL = () => {
  const envUrl = import.meta.env.VITE_API_URL?.replace(/\/$/, '');

  if (!envUrl) {
    throw new Error('[API Config]: VITE_API_URL não definida. Verifique o arquivo .env.');
  }

  if (/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?/i.test(envUrl)) {
    console.info('[API Config]: Usando API local no endereço:', envUrl);
  }

  return envUrl.endsWith('/api/v1') ? envUrl : `${envUrl}/api/v1`;
};

// Criação da instância do Axios com configurações padrão e de segurança
const api = axios.create({
  baseURL: getBaseURL(),
  timeout: 30000, // Aumentado para 30s pois o backend gratuito pode demorar a acordar
  headers: {
    'Content-Type': 'application/json',
    'Accept': 'application/json',
  },
});

export const normalizeApiError = (error) => {
  const isLoginRequest = error.config?.url?.includes('/auth/login');
  const customError = {
    message: 'Ocorreu um erro inesperado. Tente novamente mais tarde.',
    status: null,
    data: null,
    response: error.response,
  };

  if (error.response) {
    customError.status = error.response.status;
    customError.data = error.response.data;
    const isInvalidLogin = isLoginRequest &&
      (error.response.status === 401 || error.response.status === 403);

    if (isInvalidLogin) {
      customError.message = 'E-mail ou senha inválidos.';
    } else if (error.response.status >= 500) {
      customError.message = 'Algo deu errado nos bastidores. Tente novamente.';
    } else {
      customError.message = error.response.data?.message ||
        error.response.data?.messages?.[0] ||
        'Não foi possível concluir a solicitação.';
    }
  } else if (error.request) {
    if (error.code === 'ECONNABORTED') {
      customError.message = 'O servidor demorou para responder. Tente novamente.';
    } else if (isLoginRequest) {
      customError.message = 'Não foi possível entrar agora. Tente novamente.';
    } else {
      customError.message = 'Não foi possível conectar ao servidor. Tente novamente.';
    }
  } else {
    customError.message = error.message;
  }

  return customError;
};

// Interceptor de requisição (opcional, útil para enviar tokens futuramente)
api.interceptors.request.use(
  (config) => {
    const token = getToken();
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }

    if (import.meta.env.DEV) {
      console.debug('[API Request]:', {
        method: config.method?.toUpperCase(),
        url: `${config.baseURL || ''}${config.url || ''}`,
      });
    }

    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Interceptor de resposta para lidar com erros de forma global e segura
api.interceptors.response.use(
  (response) => {
    // Se a requisição deu sucesso, apenas retorna os dados.
    return response;
  },
  (error) => {
    const customError = normalizeApiError(error);

    if (error.response) {
      // Desloga o usuário se a sessão expirar, ignorando rotas de autenticação.
      // O backend atual retorna 403 quando o JWT está ausente/inválido/expirado.
      const isAuthRoute = error.config?.url?.includes('/auth/');
      const isSessionInvalid = error.response.status === 401 || error.response.status === 403;

      if (isSessionInvalid && !isAuthRoute) {
        removeToken();

        if (window.location.pathname !== '/login') {
          window.location.href = '/login';
        }
      }
    }

    console.error('[API Error]:', {
      message: customError.message,
      status: customError.status,
      url: `${error.config?.baseURL || ''}${error.config?.url || ''}`,
      code: error.code,
    });

    // Rejeitamos a promessa de forma padrão, assim o .catch() nos componentes receberá um erro previsível
    return Promise.reject(customError);
  }
);

export default api;
